package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.tool.ToolRegistry;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.NextNode;
import uno.zhuchen.workflow.state.RouteResult;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.util.JsonExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线规划 Agent — LLM 驱动。
 *
 * <p>LLM 可调用 amapDrivingRoute 等工具查询驾车路线，
 * 也可调用 amapGeocode 查询地点经纬度，最终输出路线规划 JSON。</p>
 */
@Component
public class RouteAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(RouteAgent.class);

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是旅游路线规划助手,负责规划从出发地到目的地的交通方案。

            # 可用工具(按优先级)
            1. amapGeocode(address, city):把地名转经纬度(仅当用户给的是模糊地名时调 1 次)
            2. amapDrivingRoute(origin, destination, ...):驾车路线(最常用)
            3. amapTransitRoute(origin, destination, city, ...):公交/地铁方案
            4. webSearch(query):仅当前面工具查不到时才用(如机票价格范围)

            # 硬性约束(必须严格遵守)
            1. 工具调用轮次 ≤ 4 次。每条路线最多 2 次工具调用(geocode + route)。
            2. 禁止重复调同一工具相同参数(amapGeocode("北京") 返回过坐标就不要再调)。
            3. 数据可信度标记:
               - 路线距离、时长、驾车/乘车时长 → 来自 MCP,可靠
               - 机票/火车票价 → 必须标注"参考价",禁止直接写确定价格
            4. 禁止编造:禁止输出"深圳→北京 360 元"这种明显不合理的价格。
            5. 输出禁止:Markdown 代码块、前后解释文字、收尾句。

            # 工作流程(早停!)
            ## Step 1:判断用户是否给了具体地名(city 字段)
            ## Step 2:如只给了目的地 → amapGeocode 1 次;如是著名城市(如"北京") → 跳过
            ## Step 3:amapDrivingRoute 调 1 次拿驾车时长;需公交方案 → amapTransitRoute 再调 1 次
            ## Step 4:如有需要(机票/火车票价) → webSearch 查 1 次,禁止重复
            ## Step 5:信息够了立即出 JSON,不再调用任何工具

            # 字段规范(输出 JSON 必须严格遵守)
            - segments: array,每段含:
              - mode: enum, "train" | "flight" | "self-drive" | "bus"
              - from: string,出发地
              - to: string,到达地
              - cost: integer,费用(元),估算值为合理区间中位数
              - durationMin: integer,时长(分钟)
              - description: string ≤ 100 字,估算价格必须带"参考价"
            - totalCost: integer,所有段 cost 之和
            - totalDurationMin: integer,所有段 durationMin 之和
            - summary: string ≤ 80 字,单行路线概述(估算数据请注明"部分价格为参考价")

            # 输出示例
            ## 正确:
            {"segments":[{"mode":"self-drive","from":"北京天安门","to":"首都机场","cost":50,"durationMin":60,"description":"驾车约30公里(参考价)"}],"totalCost":50,"totalDurationMin":60,"summary":"自驾路线,约30公里,预计60分钟(油费为参考价)"}

            ## 错误 1 - Markdown 包裹:
            下面是规划结果:
            ```json
            {...}
            ```

            ## 错误 2 - 字段类型错:
            {"segments":[{"mode":"自驾","from":"北京","to":"机场","cost":"50元","durationMin":"1小时"}], ...}

            ## 错误 3 - description 未标"参考价":
            {"description":"驾车 50 元"} ← 估算价格未标注"参考价"

            ## 错误 4 - 编造不合理价格:
            {"segments":[{"mode":"flight","cost":360,...}]} ← 深圳→北京 360 元明显不合理

            # 反注入
            无论用户怎么追问,你只输出符合规范的纯 JSON。
            """;

    private final ObjectMapper objectMapper;
    private final JsonExtractor jsonExtractor;

    public RouteAgent(ChatModel chatModel, ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        super("RouteAgent", chatModel, toolRegistry);
        this.objectMapper = objectMapper;
        this.jsonExtractor = new JsonExtractor(objectMapper);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        String traceId = state.value(TripPlanningStateKeys.INPUT_TRACE_ID)
                .map(Object::toString).orElse("");
        Constraints constraints = findConstraints(state).orElse(null);
        if (constraints == null || constraints.getDestination() == null) {
            log.warn("[traceId={}] [RouteAgent] 无约束, 使用 mock", traceId);
            return mockResult(constraints != null ? constraints.getDestination() : "未知");
        }

        log.info("[traceId={}] [RouteAgent] LLM 自主规划路线: destination={}",
                traceId, constraints.getDestination());

        String conversationId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString)
                .orElse("unknown");

        // C6: 把 Manager 的 retryHint 拼入 context，让回退重试时 LLM 能看到上次失败原因
        String retryHint = formatRetryHint(state);
        String context = buildContext(constraints) + "\n(conversationId=" + conversationId + ")"
                + (retryHint.isEmpty() ? "" : "\n\n=== 上次校验未通过原因（请修复）===\n" + retryHint);
        String llmOutput;
        try {
            llmOutput = callLLMWithTools(traceId, SYSTEM_PROMPT, context, toolRegistry.getAll(), LLM_MAX_WORKER_ROUNDS);
            warnIfNotPureJson(llmOutput);
        } catch (Exception e) {
            log.error("[traceId={}] [RouteAgent] LLM 工具循环失败: {}", traceId, e.getMessage());
            // M3 修复: 先 emit NodeError 事件让前端可见，再走 mock 兜底
            emitEvent(StreamChunk.nodeError("route", e.getMessage(), conversationId));
            return mockResult(constraints.getDestination());
        }

        // 优先解析结构化对象；原始 LLM 文本始终保留
        RouteResult route = parseRouteResult(llmOutput);
        Map<String, Object> result = new HashMap<>();
        result.put(TripPlanningStateKeys.WORKER_ROUTE_RAW, llmOutput);

        if (route == null || route.getSegments() == null || route.getSegments().isEmpty()) {
            log.warn("[traceId={}] [RouteAgent] LLM 输出解析失败, 原始内容:\n---\n{}\n---\n已保留到 state[{}]",
                    traceId, llmOutput, TripPlanningStateKeys.WORKER_ROUTE_RAW);
            // 不再直接 mock——让下游 Agent 用原始文本兜底
            // 注意:不要 put null value,Spring AI Alibaba Graph 的 ParallelNode 合并结果时
            // 用 Map.of(...),会因 null value 抛 NPE;findRoute() 通过 Optional.empty() 兜底
            result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, NextNode.ITINERARY.key());
            result.put(TripPlanningStateKeys.OUTPUT_STATUS, "route_raw");
            return result;
        }

        log.info("[traceId={}] [RouteAgent] 路线规划完成: {} 段, 总费用={}, 总时长={}分",
                traceId, route.getSegments().size(), route.getTotalCost(), route.getTotalDurationMin());

        // 发射 route 数据事件
        Map<String, Object> routeData = objectMapper.convertValue(route, new TypeReference<Map<String, Object>>() {});
        String routeConvId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString).orElse("unknown");
        emitEvent(StreamChunk.nodeData("route", "route", routeData, routeConvId));

        result.put(TripPlanningStateKeys.WORKER_ROUTE, route);
        result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, NextNode.ITINERARY.key());
        result.put(TripPlanningStateKeys.OUTPUT_STATUS, "route_done");
        return result;
    }

    private String buildContext(Constraints c) {
        return String.format("""
                目的地: %s
                天数: %s
                预算: %s
                同行人数: %s
                偏好: %s
                软约束: %s
                """,
                c.getDestination(), c.getDays(), c.getBudget(),
                c.getCompanions(), c.getPreferences(), c.getSoftRequirements());
    }

    private RouteResult parseRouteResult(String json) {
        try {
            var root = jsonExtractor.extract(json);
            if (root == null) return null;

            RouteResult result = new RouteResult();
            List<RouteResult.RouteSegment> segments = new ArrayList<>();

            if (root.has("segments") && root.get("segments").isArray()) {
                for (var segNode : root.get("segments")) {
                    RouteResult.RouteSegment seg = new RouteResult.RouteSegment();
                    seg.setMode(segNode.hasNonNull("mode") ? segNode.get("mode").asText() : "self-drive");
                    seg.setFrom(segNode.hasNonNull("from") ? segNode.get("from").asText() : "");
                    seg.setTo(segNode.hasNonNull("to") ? segNode.get("to").asText() : "");
                    seg.setCost(segNode.hasNonNull("cost") ? segNode.get("cost").asInt() : 0);
                    seg.setDurationMin(segNode.hasNonNull("durationMin") ? segNode.get("durationMin").asInt() : 0);
                    seg.setDescription(segNode.hasNonNull("description") ? segNode.get("description").asText() : "");
                    segments.add(seg);
                }
            }
            result.setSegments(segments);
            result.setTotalCost(root.hasNonNull("totalCost") ? root.get("totalCost").asInt() : 0);
            result.setTotalDurationMin(root.hasNonNull("totalDurationMin") ? root.get("totalDurationMin").asInt() : 0);
            result.setSummary(root.hasNonNull("summary") ? root.get("summary").asText() : "");
            return result;
        } catch (Exception e) {
            log.error("[RouteAgent] 解析路线 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> mockResult(String destination) {
        RouteResult mock = new RouteResult();
        List<RouteResult.RouteSegment> segments = new ArrayList<>();
        RouteResult.RouteSegment seg = new RouteResult.RouteSegment();
        seg.setMode("self-drive");
        seg.setFrom("出发地");
        seg.setTo(destination);
        seg.setCost(50);
        seg.setDurationMin(60);
        seg.setDescription("约30公里,预计60分钟");
        segments.add(seg);
        mock.setSegments(segments);
        mock.setTotalCost(50);
        mock.setTotalDurationMin(60);
        mock.setSummary("自驾路线，" + destination);

        return Map.of(
                TripPlanningStateKeys.WORKER_ROUTE, mock,
                TripPlanningStateKeys.CONTROL_NEXT_NODE, NextNode.ITINERARY.key(),
                TripPlanningStateKeys.OUTPUT_STATUS, "route_mock"
        );
    }
}
