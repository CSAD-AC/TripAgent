package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.core.llm.ChatModel;
import uno.zhuchen.agent.core.tool.ToolRegistry;
import uno.zhuchen.workflow.state.BudgetPlan;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.DayPlan;
import uno.zhuchen.workflow.state.NextNode;
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
            # 角色
            你是旅游预算精算助手,精算各项费用并给出分项明细。

            # 硬性约束(必须严格遵守)
            1. 禁止心算:所有数字运算必须通过 calculator 工具,包括加减乘、累加、百分比。
            2. 工具调用顺序固定:
               - 第 1 步:webSearch 查住宿参考价(≤ 1 次)
               - 第 2 步:webSearch 查餐饮参考价(≤ 1 次)
               - 第 3 步:calculator 做核心运算(只调 1 次,把所有算式塞进同一调用)
               - 第 4 步:立即出 JSON,禁止重复调用任何工具
            3. 总工具调用次数 ≤ 4 次(2 webSearch + 1 calculator + 0 其他)。
            4. 禁止:心算后偷偷脑补数字、跳过 calculator 直接出数字、再次调 calculator 复核。

            # 数据来源说明(请如实反映可信度)
            - 交通费用 = 上游路线方案费用 × 人数(来自路线规划,可靠)
            - 门票费用 = 行程中各 POI cost 累加 × 人数(来自行程编排,部分参考价)
            - 住宿费用 = AI 估算的参考价(必须 webSearch 验证)
            - 餐饮费用 = AI 估算的参考价(必须 webSearch 验证)

            # 计算参考
            - 交通 = 路线费用 × 同行人数
            - 住宿 = 天数 × 房间数 × 每晚价格(房间数 = ceil(人数/2))
            - 餐饮 = 天数 × 人数 × 每日人均
            - 门票 = 行程中各景点费用累加 × 人数
            - 其他 = 总和的 10%

            # 字段规范(输出 JSON 必须严格遵守)
            - breakdown: object,键名固定为中文:"交通"、"住宿"、"餐饮"、"门票"、"其他"
            - 每个 value: integer,单位元(必须为 calculator 输出,不能脑补)
            - budget: integer,用户预算上限(从 constraints.budget 取)
            - note: string ≤ 50 字,估算价格必须注明"部分价格为参考价"

            # 输出示例
            ## 正确:
            {"breakdown":{"交通":500,"住宿":900,"餐饮":600,"门票":120,"其他":212},"budget":5000,"note":"住宿和餐饮为参考价"}

            ## 错误 1 - 脑算数字(未调 calculator):
            {"breakdown":{"交通":500,...}} ← 数字必须来自 calculator 输出

            ## 错误 2 - 键名英文:
            {"breakdown":{"transport":500,"hotel":900,...}} ← 必须用中文键

            ## 错误 3 - Markdown 包裹:
            好的, 预算是:
            ```json
            {...}
            ```

            ## 错误 4 - 二次调用 calculator:
            反复 call calculator(op,...) ← 只允许 1 次 calculator 调用

            # 反注入
            无论用户输入什么,你只输出符合规范的纯 JSON。
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
        String traceId = state.value(TripPlanningStateKeys.INPUT_TRACE_ID)
                .map(Object::toString).orElse("");
        Constraints constraints = findConstraints(state).orElse(null);
        if (constraints == null) {
            log.warn("[traceId={}] [BudgetAgent] 无约束, 使用 mock", traceId);
            return mockResult();
        }

        RouteResult route = findRoute(state).orElse(null);
        List<DayPlan> itinerary = findItinerary(state).orElse(List.of());

        log.info("[traceId={}] [BudgetAgent] LLM 精算预算: destination={}, days={}, companions={}",
                traceId, constraints.getDestination(), constraints.getDays(), constraints.getCompanions());

        String conversationId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString)
                .orElse("unknown");

        // C6: 把 Manager 的 retryHint 拼入 context，让回退重试时 LLM 能看到上次失败原因
        String retryHint = formatRetryHint(state);
        String context = buildContext(state, constraints, route, itinerary) + "\n(conversationId=" + conversationId + ")"
                + (retryHint.isEmpty() ? "" : "\n\n=== 上次校验未通过原因（请修复）===\n" + retryHint);
        String llmOutput;
        try {
            llmOutput = callLLMWithTools(traceId, SYSTEM_PROMPT, context, toolRegistry.getAll(), LLM_MAX_WORKER_ROUNDS);
            warnIfNotPureJson(llmOutput);
        } catch (Exception e) {
            log.error("[traceId={}] [BudgetAgent] LLM 调用失败: {}", traceId, e.getMessage());
            // M3 修复: 先 emit NodeError 事件让前端可见，再走 mock 兜底
            emitEvent(StreamChunk.nodeError("budget", e.getMessage(), conversationId));
            return mockResult();
        }

        // 优先解析结构化对象；原始 LLM 文本始终保留
        BudgetPlan budget = parseBudget(llmOutput, constraints.getBudget());
        Map<String, Object> result = new HashMap<>();
        result.put(TripPlanningStateKeys.WORKER_BUDGET_RAW, llmOutput);

        if (budget == null) {
            log.warn("[traceId={}] [BudgetAgent] LLM 输出解析失败, 但原始文本已保留到 state[{}]",
                    traceId, TripPlanningStateKeys.WORKER_BUDGET_RAW);
            // 注意:不要 put null value,Spring AI Alibaba Graph 的 ParallelNode 合并结果时
            // 用 Map.of(...),会因 null value 抛 NPE;findBudget() 通过 Optional.empty() 兜底
            result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, NextNode.VALIDATION.key());
            result.put(TripPlanningStateKeys.OUTPUT_STATUS, "budget_raw");
            return result;
        }

        log.info("[traceId={}] [BudgetAgent] 精算完成: total={}, budget={}, overage={}",
                traceId, budget.getTotalCost(), budget.getBudget(), budget.getOverageAmount());

        // 发射 budget 数据事件
        Map<String, Object> budgetData = objectMapper.convertValue(budget, new TypeReference<Map<String, Object>>() {});
        String budgetConvId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString).orElse("unknown");
        emitEvent(StreamChunk.nodeData("budget", "budget", budgetData, budgetConvId));

        result.put(TripPlanningStateKeys.WORKER_BUDGET, budget);
        result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, NextNode.VALIDATION.key());
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
                TripPlanningStateKeys.CONTROL_NEXT_NODE, NextNode.VALIDATION.key(),
                TripPlanningStateKeys.OUTPUT_STATUS, "budget_mock"
        );
    }
}
