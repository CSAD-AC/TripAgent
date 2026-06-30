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
import uno.zhuchen.workflow.state.BudgetPlan;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.DayPlan;
import uno.zhuchen.workflow.state.RouteResult;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.util.JsonExtractor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预算精算 Agent — LLM 驱动。
 *
 * <p>LLM 根据路线和行程信息，精算出详细的预算分解。</p>
 */
@Component
public class BudgetAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(BudgetAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是旅游预算精算助手，负责根据用户的路线和行程信息，精算出详细的预算分解。
            你的计算准确，回复精简，完成计算后立即输出最终结果，避免多余步骤。

            === 输入 ===
            你会收到约束（目的地、天数、预算上限、人数）以及已规划的路线和每日行程。

            === 数据来源说明 ===
            本预算中的价格基于以下来源，请如实反映可信度：
            ✅ 交通费用 = 上游路线方案中的费用 × 人数（来自路线规划）
            ✅ 门票费用 = 行程中各景点标注的 cost 之和（来自行程编排，部分为参考价）
            ⚠️ 住宿费用 = AI 根据目的地和天数估算的参考价
            ⚠️ 餐饮费用 = AI 根据目的地消费水平估算的参考价

            === 计算参考 ===
            - 交通: 路线费用 × 同行人数
            - 住宿: 天数 × 房间数 × 每晚价格（房间数=(人数+1)/2 — 用 webSearch 查当地酒店参考价）
            - 餐饮: 天数 × 人数 × 每日餐饮（用 webSearch 查当地人均消费）
            - 门票: 行程中各景点费用累加 × 人数
            - 其他: 总和的 10%

            === 执行规则（重要）===
            1. 先用 webSearch 查询目的地住宿和餐饮参考价格，获取合理范围
            2. 再调用 calculator 做核心运算（累加各项费用）
            3. 用 calculator 得到结果后，**立即**输出最终 JSON
            4. 不要重复调用 calculator，不要再次调用其他工具

            === 输出要求 ===
            直接输出纯 JSON，不要 markdown 代码块，不要 ```json 标记，不要任何解释文字。
            字段含义：
            breakdown: 分项费用对象（键为中文名称，值为整数元，顺序：交通→住宿→餐饮→门票→其他）
            budget: 用户预算上限（整数元）
            note: 备注，**如果 breakdown 中的价格包含估算数据，请注明"部分价格为参考价"**

            示例输出（纯 JSON，无其他文字）：
            {"breakdown":{"交通":500,"住宿":900,"餐饮":600,"门票":120,"其他":212},"budget":5000,"note":"住宿和餐饮为参考价"}
            """;

    private final ObjectMapper objectMapper;
    private final JsonExtractor jsonExtractor;

    public BudgetAgent(ChatModel chatModel, ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        super("BudgetAgent", chatModel, toolRegistry);
        this.objectMapper = objectMapper;
        this.jsonExtractor = new JsonExtractor(objectMapper);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        Constraints constraints = findConstraints(state).orElse(null);
        if (constraints == null) {
            log.warn("[BudgetAgent] 无约束, 使用 mock");
            return mockResult();
        }

        RouteResult route = findRoute(state).orElse(null);
        List<DayPlan> itinerary = findItinerary(state).orElse(List.of());

        log.info("[BudgetAgent] LLM 精算预算: destination={}, days={}, companions={}",
                constraints.getDestination(), constraints.getDays(), constraints.getCompanions());

        String conversationId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString)
                .orElse("unknown");

        String context = buildContext(state, constraints, route, itinerary) + "\n(conversationId=" + conversationId + ")";
        String llmOutput;
        try {
            llmOutput = callLLMWithTools(SYSTEM_PROMPT, context, toolRegistry.getAll(), 15);
        } catch (Exception e) {
            log.error("[BudgetAgent] LLM 调用失败: {}", e.getMessage());
            return mockResult();
        }

        // 优先解析结构化对象；原始 LLM 文本始终保留
        BudgetPlan budget = parseBudget(llmOutput, constraints.getBudget());
        Map<String, Object> result = new HashMap<>();
        result.put(TripPlanningStateKeys.WORKER_BUDGET_RAW, llmOutput);

        if (budget == null) {
            log.warn("[BudgetAgent] LLM 输出解析失败, 但原始文本已保留到 state[{}]",
                    TripPlanningStateKeys.WORKER_BUDGET_RAW);
            // 注意:不要 put null value,Spring AI Alibaba Graph 的 ParallelNode 合并结果时
            // 用 Map.of(...),会因 null value 抛 NPE;findBudget() 通过 Optional.empty() 兜底
            result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, "validation");
            result.put(TripPlanningStateKeys.OUTPUT_STATUS, "budget_raw");
            return result;
        }

        log.info("[BudgetAgent] 精算完成: total={}, budget={}, overage={}",
                budget.getTotalCost(), budget.getBudget(), budget.getOverageAmount());

        // 发射 budget 数据事件
        Map<String, Object> budgetData = objectMapper.convertValue(budget, new TypeReference<Map<String, Object>>() {});
        String budgetConvId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString).orElse("unknown");
        emitEvent(StreamChunk.nodeData("budget", "budget", budgetData, budgetConvId));

        result.put(TripPlanningStateKeys.WORKER_BUDGET, budget);
        result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, "validation");
        result.put(TripPlanningStateKeys.OUTPUT_STATUS, "budget_done");
        return result;
    }

    private String buildContext(OverAllState state, Constraints c, RouteResult r, List<DayPlan> itinerary) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
                目的地: %s
                天数: %d
                预算上限: %d 元
                同行人数: %d
                """,
                c.getDestination(), c.getDays(), c.getBudget(), c.getCompanions()));

        if (r != null && r.getSegments() != null && !r.getSegments().isEmpty()) {
            sb.append("\n路线方案:\n");
            for (RouteResult.RouteSegment seg : r.getSegments()) {
                sb.append(String.format("  %s: %s → %s (%d元, %d分)%n",
                        seg.getMode(), seg.getFrom(), seg.getTo(),
                        seg.getCost(), seg.getDurationMin()));
            }
            sb.append(String.format("  总费用: %d元, 总时长: %d分%n", r.getTotalCost(), r.getTotalDurationMin()));
        } else {
            // 兜底：使用原始 LLM 输出文本
            state.value(TripPlanningStateKeys.WORKER_ROUTE_RAW)
                    .ifPresent(raw -> sb.append("\n路线方案(原始):\n").append(raw).append("\n"));
        }

        if (itinerary != null && !itinerary.isEmpty()) {
            sb.append("\n每日行程:\n");
            for (DayPlan day : itinerary) {
                sb.append(String.format("  第%d天 (住宿: %s)%n",
                        day.getDayIndex(), day.getAccommodation()));
                if (day.getPois() != null) {
                    for (DayPlan.PoiActivity poi : day.getPois()) {
                        sb.append(String.format("    - %s (%s, %d元)%n",
                                poi.getName(), poi.getType(), poi.getCost()));
                    }
                }
            }
        } else {
            // 兜底：使用原始 LLM 输出文本
            state.value(TripPlanningStateKeys.WORKER_ITINERARY_RAW)
                    .ifPresent(raw -> sb.append("\n每日行程(原始):\n").append(raw).append("\n"));
        }

        return sb.toString();
    }

    private BudgetPlan parseBudget(String json, Integer budgetLimit) {
        try {
            var root = jsonExtractor.extract(json);
            if (root == null) return null;

            Map<String, Integer> breakdown = new LinkedHashMap<>();
            if (root.has("breakdown")) {
                var bd = root.get("breakdown");
                bd.fieldNames().forEachRemaining(key ->
                        breakdown.put(key, bd.get(key).asInt(0)));
            }

            // 优先用 LLM 输出的 budget，否则用传入的
            int budget = root.hasNonNull("budget") ? root.get("budget").asInt() :
                    (budgetLimit != null ? budgetLimit : 3000);

            return BudgetPlan.of(breakdown, budget);
        } catch (Exception e) {
            log.error("[BudgetAgent] 解析预算 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> mockResult() {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("交通", 100);
        breakdown.put("住宿", 900);
        breakdown.put("餐饮", 600);
        breakdown.put("门票", 120);
        breakdown.put("其他", 172);

        BudgetPlan mock = BudgetPlan.of(breakdown, 5000);
        return Map.of(
                TripPlanningStateKeys.WORKER_BUDGET, mock,
                TripPlanningStateKeys.CONTROL_NEXT_NODE, "validation",
                TripPlanningStateKeys.OUTPUT_STATUS, "budget_mock"
        );
    }
}
