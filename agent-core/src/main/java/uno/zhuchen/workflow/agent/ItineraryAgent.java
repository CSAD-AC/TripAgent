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
import uno.zhuchen.workflow.state.DayPlan;
import uno.zhuchen.workflow.state.NextNode;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.util.JsonExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程编排 Agent — LLM 驱动。
 *
 * <p>LLM 自行决定搜索哪些 POI、查询天气，并编排每日行程。</p>
 */
@Component
public class ItineraryAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(ItineraryAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是旅游行程规划助手，负责为用户编排每日的详细行程。

            你可用的工具：
            - amapPoiSearch(keywords, city, offset, page): 搜索景点/餐厅（名称、类型、位置）
            - amapWeather(city, extensions): 查询天气预报
            - amapGeocode(address, city): 查询地点经纬度
            - amapPoiAround(longitude, latitude, radius, keywords): 周边搜索
            - webSearch(query): 搜索互联网获取景点介绍、门票价格范围、营业时间
            - pageFetch(url): 获取网页内容，查看详细的旅游攻略
            - calculator(op, x, y, values, part, total): 精确计算器

            === 数据来源分级（必须遵守）===
            ✅ 可靠数据（来自工具）:
              - 景点/餐厅名称、类型、位置 → amapPoiSearch
              - 天气预报 → amapWeather
              - 驾车/乘车时长估算 → amapDrivingRoute

            ⚠️ 估算数据（AI 知识）:
              - 门票价格、餐饮人均消费 → 用知识给出合理范围
              - 在 note 中标注"参考价约XX元"，不写成确定价格
              - 示例: "参考价约60元" ✓ | "门票60元" ✗

            ❌ 禁止行为:
              - 不能编造景点名称（必须来自 amapPoiSearch）
              - 不能编造门票价格（必须标注"参考价"）
              - 不能写明显不合理的价格（如故宫门票 10元）

            === 工作流程 ===
            1. 先调 amapPoiSearch 搜索目的地有什么景点、餐厅（一次多关键词）
            2. 查询当地天气
            3. 根据天数、预算、偏好编排每日行程
            4. 用 webSearch 确认关键景点的门票价格范围
            5. 输出最终 JSON

            效率要求：尽量减少 LLM 来回交互次数，需要多个信息时一次并行获取。

            === 输出要求（重要）===
            直接输出纯 JSON，不要 markdown 代码块，不要 ```json 标记，不要任何解释文字。
            只输出 JSON，不要包含其他任何内容。

            JSON 字段含义：
              days: 天数数组
                dayIndex: 第几天（从1开始）
                pois: 当日活动列表
                  name: 名称
                  type: "attraction"/"restaurant"/"hotel"/"transport"/"activity"
                  durationMin: 预计停留分钟数
                  cost: 费用（整数元，估算值需标注）
                  note: 备注，**如为估算价格请注明"参考价"**
                weather: 天气摘要
                dining: 餐饮建议
                accommodation: 住宿建议

            示例输出（纯 JSON，无其他文字）：
            {"days":[{"dayIndex":1,"pois":[{"name":"故宫","type":"attraction","durationMin":180,"cost":60,"note":"参考价约60元，需提前预约"}],"weather":"晴","dining":"全聚德烤鸭（参考价约150元/人）","accommodation":"如家酒店（参考价约300元/晚）"}]}
            """;

    private final ObjectMapper objectMapper;
    private final JsonExtractor jsonExtractor;

    public ItineraryAgent(ChatModel chatModel, ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        super("ItineraryAgent", chatModel, toolRegistry);
        this.objectMapper = objectMapper;
        this.jsonExtractor = new JsonExtractor(objectMapper);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        Constraints constraints = findConstraints(state).orElse(null);
        if (constraints == null || constraints.getDestination() == null) {
            log.warn("[ItineraryAgent] 无约束, 使用 mock");
            return mockResult();
        }

        log.info("[ItineraryAgent] LLM 自主编排行程: destination={}, days={}",
                constraints.getDestination(), constraints.getDays());

        String conversationId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString)
                .orElse("unknown");

        // C6: 把 Manager 的 retryHint 拼入 context，让回退重试时 LLM 能看到上次失败原因
        String retryHint = formatRetryHint(state);
        String context = buildContext(constraints) + "\n(conversationId=" + conversationId + ")"
                + (retryHint.isEmpty() ? "" : "\n\n=== 上次校验未通过原因（请修复）===\n" + retryHint);
        String llmOutput;
        try {
            llmOutput = callLLMWithTools(SYSTEM_PROMPT, context, toolRegistry.getAll(), 15);
        } catch (Exception e) {
            log.error("[ItineraryAgent] LLM 工具循环失败: {}", e.getMessage());
            // M3 修复: 先 emit NodeError 事件让前端可见，再走 mock 兜底
            emitEvent(StreamChunk.nodeError("itinerary", e.getMessage(), conversationId));
            return mockResult();
        }

        // 优先解析结构化对象；原始 LLM 文本始终保留
        List<DayPlan> itinerary = parseItinerary(llmOutput);
        Map<String, Object> result = new HashMap<>();
        result.put(TripPlanningStateKeys.WORKER_ITINERARY_RAW, llmOutput);

        if (itinerary == null || itinerary.isEmpty()) {
            log.warn("[ItineraryAgent] LLM 输出解析失败, 原始内容:\n---\n{}\n---\n已保留到 state[{}]",
                    llmOutput, TripPlanningStateKeys.WORKER_ITINERARY_RAW);
            // 注意:不要 put null value,Spring AI Alibaba Graph 的 ParallelNode 合并结果时
            // 用 Map.of(...),会因 null value 抛 NPE;findItinerary() 通过 Optional.empty() 兜底
            result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, NextNode.BUDGET.key());
            result.put(TripPlanningStateKeys.OUTPUT_STATUS, "itinerary_raw");
            return result;
        }

        log.info("[ItineraryAgent] 行程编排完成: {} 天", itinerary.size());

        // 发射 dayplans 数据事件
        List<Map<String, Object>> daysList = itinerary.stream()
                .map(day -> objectMapper.convertValue(day, new TypeReference<Map<String, Object>>() {}))
                .toList();
        String itineraryConvId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString).orElse("unknown");
        emitEvent(StreamChunk.nodeData("itinerary", "dayplans", Map.of("days", daysList), itineraryConvId));

        result.put(TripPlanningStateKeys.WORKER_ITINERARY, itinerary);
        result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, NextNode.BUDGET.key());
        result.put(TripPlanningStateKeys.OUTPUT_STATUS, "itinerary_done");
        return result;
    }

    private String buildContext(Constraints c) {
        return String.format("""
                目的地: %s
                天数: %d
                预算: %d 元
                同行人数: %d
                偏好: %s
                软约束: %s
                """,
                c.getDestination(), c.getDays(), c.getBudget(),
                c.getCompanions(), c.getPreferences(), c.getSoftRequirements());
    }

    private List<DayPlan> parseItinerary(String json) {
        try {
            var root = jsonExtractor.extract(json);
            if (root == null) return null;

            List<DayPlan> days = new ArrayList<>();
            if (root.has("days") && root.get("days").isArray()) {
                for (var dayNode : root.get("days")) {
                    DayPlan day = new DayPlan();
                    day.setDayIndex(dayNode.hasNonNull("dayIndex") ? dayNode.get("dayIndex").asInt() : 1);

                    List<DayPlan.PoiActivity> pois = new ArrayList<>();
                    if (dayNode.has("pois") && dayNode.get("pois").isArray()) {
                        for (var poiNode : dayNode.get("pois")) {
                            DayPlan.PoiActivity poi = new DayPlan.PoiActivity();
                            poi.setName(poiNode.hasNonNull("name") ? poiNode.get("name").asText() : "");
                            poi.setType(poiNode.hasNonNull("type") ? poiNode.get("type").asText() : "attraction");
                            poi.setDurationMin(poiNode.hasNonNull("durationMin") ? poiNode.get("durationMin").asInt() : 0);
                            poi.setCost(poiNode.hasNonNull("cost") ? poiNode.get("cost").asInt() : 0);
                            poi.setNote(poiNode.hasNonNull("note") ? poiNode.get("note").asText() : "");
                            pois.add(poi);
                        }
                    }
                    day.setPois(pois);
                    day.setWeather(dayNode.hasNonNull("weather") ? dayNode.get("weather").asText() : null);
                    day.setDining(dayNode.hasNonNull("dining") ? dayNode.get("dining").asText() : null);
                    day.setAccommodation(dayNode.hasNonNull("accommodation") ? dayNode.get("accommodation").asText() : null);
                    days.add(day);
                }
            }
            return days;
        } catch (Exception e) {
            log.error("[ItineraryAgent] 解析行程 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> mockResult() {
        List<DayPlan> mockDays = new ArrayList<>();

        DayPlan day1 = new DayPlan();
        day1.setDayIndex(1);
        List<DayPlan.PoiActivity> pois1 = new ArrayList<>();
        pois1.add(new DayPlan.PoiActivity("示例景点A", "attraction", 120, 0, "热门景点"));
        pois1.add(new DayPlan.PoiActivity("示例餐厅", "restaurant", 60, 100, "当地美食"));
        day1.setPois(pois1);
        day1.setAccommodation("示例酒店");
        mockDays.add(day1);

        DayPlan day2 = new DayPlan();
        day2.setDayIndex(2);
        List<DayPlan.PoiActivity> pois2 = new ArrayList<>();
        pois2.add(new DayPlan.PoiActivity("示例景点B", "attraction", 180, 0, "文化景点"));
        day2.setPois(pois2);
        day2.setAccommodation("示例酒店");
        mockDays.add(day2);

        return Map.of(
                TripPlanningStateKeys.WORKER_ITINERARY, mockDays,
                TripPlanningStateKeys.CONTROL_NEXT_NODE, NextNode.BUDGET.key(),
                TripPlanningStateKeys.OUTPUT_STATUS, "itinerary_mock"
        );
    }
}
