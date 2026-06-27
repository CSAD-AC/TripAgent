package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.workflow.state.BudgetPlan;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.DayPlan;
import uno.zhuchen.workflow.state.RouteResult;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预算精算 Agent — Worker 层（串行）
 *
 * 输入: Constraints + RouteResult + List<DayPlan>（三者必须都齐备）
 * 输出: BudgetPlan（分项费用 + 是否超预算）
 *
 * 为什么串行（不与 Route/Itinerary 并行）:
 * 预算依赖具体景点门票和交通费用，必须等 Worker 输出后才能精算。
 * 这也是 Route + Itinerary 之间的并行边界设计。
 *
 * Day 2: 空壳 — 基于 mock 输入返回 mock 预算，Day 4 接入完整计算
 */
@Component
public class BudgetAgent extends BaseAgent {

    public BudgetAgent(ChatModel chatModel) {
        super("BudgetAgent", chatModel);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        // Day 4 实现: 真实费用计算
        log.warn("[BudgetAgent] Day 2 空壳 — 待 Day 4 实现");

        Constraints constraints = findConstraints(state).orElse(null);
        RouteResult route = findRoute(state).orElse(null);
        List<DayPlan> itinerary = findItinerary(state).orElse(List.of());

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("交通", 500);
        breakdown.put("住宿", 800);
        breakdown.put("餐饮", 400);
        breakdown.put("门票", 300);
        breakdown.put("其他", 200);
        BudgetPlan mock = BudgetPlan.of(breakdown, constraints != null ? constraints.getBudget() : 5000);

        return Map.of(
                TripPlanningStateKeys.WORKER_BUDGET, mock,
                TripPlanningStateKeys.CONTROL_NEXT_NODE, "validation"
        );
    }
}
