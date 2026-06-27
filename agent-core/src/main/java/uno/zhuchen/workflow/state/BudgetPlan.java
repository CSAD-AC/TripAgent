package uno.zhuchen.workflow.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预算精算结果 — BudgetAgent 输出
 *
 * 基于 RouteResult + List<DayPlan> + Constraints 计算。
 * 字段语义:
 * - breakdown: 分项费用（保留插入顺序便于报告展示）
 * - isOverBudget: 是否超出 Constraints.budget
 * - overageAmount: 超出金额（仅当 isOverBudget=true 时有意义）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BudgetPlan {

    /** 分项费用（顺序: 交通 -> 住宿 -> 餐饮 -> 门票 -> 其他） */
    @Builder.Default
    private Map<String, Integer> breakdown = new LinkedHashMap<>();

    /** 总费用（breakdown 之和） */
    private Integer totalCost;

    /** 用户预算（来自 Constraints.budget） */
    private Integer budget;

    /** 是否超预算 */
    private Boolean isOverBudget;

    /** 超出金额（isOverBudget=true 时 = totalCost - budget） */
    private Integer overageAmount;

    /**
     * 用预算 + 分项费用构造 BudgetPlan，自动计算 total / overage
     */
    public static BudgetPlan of(Map<String, Integer> breakdown, Integer budget) {
        int total = breakdown.values().stream().mapToInt(Integer::intValue).sum();
        int overage = budget != null ? Math.max(0, total - budget) : 0;
        return BudgetPlan.builder()
                .breakdown(breakdown)
                .totalCost(total)
                .budget(budget)
                .isOverBudget(overage > 0)
                .overageAmount(overage)
                .build();
    }
}
