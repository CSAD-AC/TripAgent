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
            你是旅游路线规划助手，负责为用户规划从出发地到目的地的交通方案。

            你可用的工具：
            - amapDrivingRoute(origin, destination, city, strategy): 查询驾车路线（路程、时长）
            - amapGeocode(address, city): 查询地点经纬度
            - amapTransitRoute(origin, destination, city, ...): 查询公共交通路线
            - webSearch(query): 搜索互联网获取交通信息、班次、票价范围
            - pageFetch(url): 获取网页内容

            === 数据来源分级（必须遵守）===
            ✅ 可靠数据（来自工具）:
              - 路线距离、预计驾车/乘车时长 → amapDrivingRoute / amapTransitRoute
              - 地点坐标 → amapGeocode
              - 公共交通班次信息 → amapTransitRoute / webSearch

            ⚠️ 估算数据（AI 知识 + 可选 webSearch 参考）:
              - 机票价格、高铁票价、巴士票价 → 用知识给出合理估算
              - 参考价格范围：国内航班经济舱 500-2000元，高铁二等座约 0.4-0.6元/公里
              - 在 description 中标注"参考价"，不能写成确定价格

            ❌ 禁止行为:
              - 不能编造明显不合理的价格（如深圳→北京 360元）
              - 不能输出没有任何来源的价格数据
              - 不能为了凑整数随意写价格

            === 工作流程 ===
            1. 先用 amapGeocode 查询出发地和目的地的坐标
            2. 用 amapDrivingRoute / amapTransitRoute 获取驾车/公共交通路线和时长
            3. 如需航班/火车信息，用 webSearch 搜索实时票价范围作为参考
            4. 综合所有信息输出最终 JSON

            效率要求：尽量减少 LLM 来回交互次数，需要多个信息时一次并行获取。

            === 输出要求（重要）===
            直接输出纯 JSON，不要 markdown 代码块，不要 ```json 标记，不要任何解释文字。
            只输出 JSON，不要包含其他任何内容。

            JSON 字段含义：
            segments: 路线段列表
              mode: "train"/"flight"/"self-drive"/"bus"
              from: 出发地
              to: 目的地
              cost: 费用（整数元，估算值用合理范围中间值）
              durationMin: 时长（分钟）
              description: 补充说明，**如果是估算价格必须标注"参考价"**
            totalCost: 总费用（整数元）
            totalDurationMin: 总时长（分钟）
            summary: 路线摘要，如包含估算数据请注明"部分价格为参考价"

            示例输出（纯 JSON，无其他文字）：
            {"segments":[{"mode":"self-drive","from":"北京天安门","to":"首都机场","cost":50,"durationMin":60,"description":"驾车约30公里（参考价）"}],"totalCost":50,"totalDurationMin":60,"summary":"自驾路线，约30公里，预计60分钟（油费为参考价）"}
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
        Constraints constraints = findConstraints(state).orElse(null);
        if (constraints == null || constraints.getDestination() == null) {
            log.warn("[RouteAgent] 无约束, 使用 mock");
            return mockResult(constraints != null ? constraints.getDestination() : "未知");
        }

        log.info("[RouteAgent] LLM 自主规划路线: destination={}", constraints.getDestination());

        String conversationId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString)
                .orElse("unknown");

        String context = buildContext(constraints) + "\n(conversationId=" + conversationId + ")";
        String llmOutput;
        try {
            llmOutput = callLLMWithTools(SYSTEM_PROMPT, context, toolRegistry.getAll(), 15);
        } catch (Exception e) {
            log.error("[RouteAgent] LLM 工具循环失败: {}", e.getMessage());
            return mockResult(constraints.getDestination());
        }

        // 优先解析结构化对象；原始 LLM 文本始终保留
        RouteResult route = parseRouteResult(llmOutput);
        Map<String, Object> result = new HashMap<>();
        result.put(TripPlanningStateKeys.WORKER_ROUTE_RAW, llmOutput);

        if (route == null || route.getSegments() == null || route.getSegments().isEmpty()) {
            log.warn("[RouteAgent] LLM 输出解析失败, 原始内容:\n---\n{}\n---\n已保留到 state[{}]",
                    llmOutput, TripPlanningStateKeys.WORKER_ROUTE_RAW);
            // 不再直接 mock——让下游 Agent 用原始文本兜底
            result.put(TripPlanningStateKeys.WORKER_ROUTE, null);
            result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, "itinerary");
            result.put(TripPlanningStateKeys.OUTPUT_STATUS, "route_raw");
            return result;
        }

        log.info("[RouteAgent] 路线规划完成: {} 段, 总费用={}, 总时长={}分",
                route.getSegments().size(), route.getTotalCost(), route.getTotalDurationMin());

        // 发射 route 数据事件
        Map<String, Object> routeData = objectMapper.convertValue(route, new TypeReference<Map<String, Object>>() {});
        String routeConvId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString).orElse("unknown");
        emitEvent(StreamChunk.nodeData("route", "route", routeData, routeConvId));

        result.put(TripPlanningStateKeys.WORKER_ROUTE, route);
        result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, "itinerary");
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
                TripPlanningStateKeys.CONTROL_NEXT_NODE, "itinerary",
                TripPlanningStateKeys.OUTPUT_STATUS, "route_mock"
        );
    }
}
