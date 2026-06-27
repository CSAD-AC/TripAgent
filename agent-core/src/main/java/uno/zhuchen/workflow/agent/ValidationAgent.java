package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.workflow.state.BudgetPlan;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.DayPlan;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.state.ValidationReport;

import java.util.List;
import java.util.Map;

/**
 * 校验 Agent — Validator 层（核心创新点）
 *
 * 校验两类约束:
 * 1. 静态约束（数值比较）: budget / days / companions
 *    例: BudgetPlan.totalCost 是否超出 Constraints.budget
 * 2. 软约束（LLM 推理）: Constraints.softRequirements
 *    例: "中途要去游乐园" — LLM 看 itinerary 是否满足
 *
 * 通过 state[next_node] 决定走向:
 * - passed=true → "report"
 * - passed=false → "manager"（回退主管）
 *
 * Day 2: 空壳 — 静态校验 + mock 软约束通过，Day 5 完整实现 LLM 推理
 */
@Component
public class ValidationAgent extends BaseAgent {

    public ValidationAgent(ChatModel chatModel) {
        super("ValidationAgent", chatModel);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        log.warn("[ValidationAgent] Day 2 空壳 — 待 Day 5 实现");

        Constraints constraints = findConstraints(state).orElse(null);
        BudgetPlan budget = findBudget(state).orElse(null);
        List<DayPlan> itinerary = findItinerary(state).orElse(List.of());

        // Day 2 仅做最简静态校验
        boolean overBudget = budget != null && Boolean.TRUE.equals(budget.getIsOverBudget());

        ValidationReport report = ValidationReport.builder()
                .passed(!overBudget)
                .failures(overBudget
                        ? List.of(ValidationReport.Failure.builder()
                                .dimension("static.budget")
                                .requirement("预算不超支")
                                .reason("总费用超过预算")
                                .suggestion("减少景点或降低住宿标准")
                                .build())
                        : List.of())
                .warnings(List.of("[MOCK] 软约束校验待 Day 5 实现"))
                .build();

        return Map.of(
                TripPlanningStateKeys.VALIDATION_REPORT, report,
                TripPlanningStateKeys.CONTROL_NEXT_NODE,
                report.getPassed() ? "report" : "manager"
        );
    }
}
