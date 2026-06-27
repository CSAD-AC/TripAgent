package uno.zhuchen.workflow.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务 POJO 单元测试
 *
 * 覆盖: Builder、默认值、helper 方法（如 isComplete / of 工厂）
 */
class PojoTest {

    // ============ Constraints ============

    @Test
    @DisplayName("Constraints.Builder 基础用法")
    void constraintsBuilder() {
        Constraints c = Constraints.builder()
                .destination("北京")
                .days(3)
                .budget(5000)
                .companions(2)
                .preferences(List.of("自然", "文化"))
                .softRequirements(List.of("中途要去游乐园"))
                .build();

        assertEquals("北京", c.getDestination());
        assertEquals(3, c.getDays());
        assertEquals(5000, c.getBudget());
        assertEquals(2, c.getCompanions());
        assertEquals(2, c.getPreferences().size());
        assertEquals(1, c.getSoftRequirements().size());
    }

    @Test
    @DisplayName("Constraints 默认空集合（避免 NPE）")
    void defaultsAreEmpty() {
        Constraints c = Constraints.builder().build();
        assertNotNull(c.getPreferences());
        assertNotNull(c.getSoftRequirements());
        assertTrue(c.getPreferences().isEmpty());
        assertTrue(c.getSoftRequirements().isEmpty());
    }

    @Test
    @DisplayName("Constraints.isComplete: 必填字段齐备 → true")
    void isCompleteWhenAllSet() {
        Constraints c = Constraints.builder()
                .destination("北京").days(3).budget(5000).companions(2).build();
        assertTrue(c.isComplete());
    }

    @Test
    @DisplayName("Constraints.isComplete: 缺 destination → false")
    void isIncompleteWhenMissingDestination() {
        Constraints c = Constraints.builder().days(3).budget(5000).companions(2).build();
        assertFalse(c.isComplete());
        assertEquals(List.of("destination"), c.missingRequiredFields());
    }

    @Test
    @DisplayName("Constraints.isComplete: 多个必填字段缺失")
    void multipleMissingFields() {
        Constraints c = Constraints.builder().destination("北京").build();
        assertFalse(c.isComplete());
        List<String> missing = c.missingRequiredFields();
        assertEquals(3, missing.size());
        assertTrue(missing.contains("days"));
        assertTrue(missing.contains("budget"));
        assertTrue(missing.contains("companions"));
    }

    // ============ RouteResult ============

    @Test
    @DisplayName("RouteResult 默认 segments 为空列表")
    void routeResultDefaults() {
        RouteResult r = RouteResult.builder().build();
        assertNotNull(r.getSegments());
        assertTrue(r.getSegments().isEmpty());
    }

    @Test
    @DisplayName("RouteResult.RouteSegment 嵌套 Builder")
    void routeSegmentBuilder() {
        RouteResult.RouteSegment seg = RouteResult.RouteSegment.builder()
                .mode("train")
                .from("上海")
                .to("北京")
                .cost(553)
                .durationMin(330)
                .description("高铁 G7")
                .build();

        assertEquals("train", seg.getMode());
        assertEquals(553, seg.getCost());
    }

    // ============ DayPlan ============

    @Test
    @DisplayName("DayPlan.PoiActivity 嵌套")
    void dayPlanPoi() {
        DayPlan.PoiActivity poi = DayPlan.PoiActivity.builder()
                .name("故宫")
                .type("attraction")
                .durationMin(180)
                .cost(60)
                .note("需提前 7 天预约")
                .build();

        assertEquals("故宫", poi.getName());
        assertEquals(60, poi.getCost());
    }

    // ============ BudgetPlan ============

    @Test
    @DisplayName("BudgetPlan.of: 预算充足 → 不超支")
    void budgetPlanNotOver() {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("交通", 500);
        breakdown.put("住宿", 800);
        breakdown.put("餐饮", 400);
        breakdown.put("门票", 300);

        BudgetPlan plan = BudgetPlan.of(breakdown, 5000);

        assertEquals(2000, plan.getTotalCost());
        assertEquals(5000, plan.getBudget());
        assertFalse(plan.getIsOverBudget());
        assertEquals(0, plan.getOverageAmount());
    }

    @Test
    @DisplayName("BudgetPlan.of: 预算超支 → 计算超出金额")
    void budgetPlanOver() {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("交通", 1000);
        breakdown.put("住宿", 2000);
        breakdown.put("餐饮", 1500);
        breakdown.put("门票", 1500);

        BudgetPlan plan = BudgetPlan.of(breakdown, 5000);

        assertEquals(6000, plan.getTotalCost());
        assertTrue(plan.getIsOverBudget());
        assertEquals(1000, plan.getOverageAmount());
    }

    @Test
    @DisplayName("BudgetPlan.of: 预算恰好等于支出")
    void budgetPlanExactlyAtLimit() {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("交通", 5000);

        BudgetPlan plan = BudgetPlan.of(breakdown, 5000);
        assertFalse(plan.getIsOverBudget());
        assertEquals(0, plan.getOverageAmount());
    }

    @Test
    @DisplayName("BudgetPlan.of: 空 breakdown → totalCost=0")
    void budgetPlanEmpty() {
        BudgetPlan plan = BudgetPlan.of(new LinkedHashMap<>(), 5000);
        assertEquals(0, plan.getTotalCost());
        assertFalse(plan.getIsOverBudget());
    }

    // ============ ValidationReport ============

    @Test
    @DisplayName("ValidationReport 默认 passed=true")
    void validationReportDefaultPassed() {
        ValidationReport r = ValidationReport.builder().build();
        assertTrue(r.getPassed());
        assertNotNull(r.getFailures());
        assertNotNull(r.getWarnings());
    }

    @Test
    @DisplayName("ValidationReport.Failure 嵌套")
    void failureBuilder() {
        ValidationReport.Failure f = ValidationReport.Failure.builder()
                .dimension("soft.requirement")
                .requirement("中途要去游乐园")
                .reason("行程未包含游乐园")
                .suggestion("替换王府井为环球影城")
                .build();

        assertEquals("soft.requirement", f.getDimension());
        assertEquals("中途要去游乐园", f.getRequirement());
    }
}
