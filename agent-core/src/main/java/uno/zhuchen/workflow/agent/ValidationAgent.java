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
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.state.ValidationReport;
import uno.zhuchen.workflow.util.JsonExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 校验 Agent — LLM 驱动。
 *
 * <p>LLM 对行程方案进行全面校验，包括预算、软约束、行程合理性。</p>
 */
@Component
public class ValidationAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(ValidationAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是旅游方案校验助手，负责检查已经生成的旅行计划是否合理。

            === 校验项 ===
            1. 预算校验: 总费用是否在预算范围内（可用 calculator 工具精确计算）
            2. 行程校验: 天数是否匹配、每日安排是否合理
            3. 软约束校验: 用户提的个性化需求是否都已满足
            4. 综合评估: 整体方案的合理性

            === 输出要求 ===
            以严格 JSON 格式输出（不要其他文字），字段含义：
            passed: true/false 是否通过
            failures: 失败项数组
              dimension: 失败维度 ("static.budget"/"static.days"/"soft.requirement")
              requirement: 具体字段或软约束原文
              reason: 失败原因
              suggestion: 修改建议
            warnings: 警告字符串数组
            summary: 综合评语

            示例（通过）：
            {"passed":true,"failures":[],"warnings":["预算略有结余"],"summary":"方案合理"}

            示例（不通过）：
            {"passed":false,"failures":[{"dimension":"soft.requirement","requirement":"中途去游乐园","reason":"行程中未包含游乐园","suggestion":"在第2天加入环球影城"}],"warnings":[],"summary":"软约束未满足"}
            """;

    private final ObjectMapper objectMapper;
    private final JsonExtractor jsonExtractor;

    public ValidationAgent(ChatModel chatModel, ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        super("ValidationAgent", chatModel, toolRegistry);
        this.objectMapper = objectMapper;
        this.jsonExtractor = new JsonExtractor(objectMapper);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        Constraints constraints = findConstraints(state).orElse(null);
        if (constraints == null) {
            log.warn("[ValidationAgent] 无约束, 跳过校验");
            return mockPassed();
        }

        BudgetPlan budget = findBudget(state).orElse(null);
        List<DayPlan> itinerary = findItinerary(state).orElse(List.of());

        log.info("[ValidationAgent] LLM 校验方案: destination={}, days={}, budget={}",
                constraints.getDestination(), constraints.getDays(), constraints.getBudget());

        String context = buildContext(state, constraints, budget, itinerary);
        String llmJson;
        try {
            llmJson = callLLM(SYSTEM_PROMPT, context);
        } catch (Exception e) {
            log.error("[ValidationAgent] LLM 调用失败: {}", e.getMessage());
            return mockPassed();
        }

        ValidationReport report = parseValidation(llmJson);
        if (report == null) {
            log.warn("[ValidationAgent] LLM 输出解析失败, 默认通过");
            return mockPassed();
        }

        log.info("[ValidationAgent] 校验完成: passed={}, failures={}, warnings={}",
                report.getPassed(), report.getFailures().size(), report.getWarnings().size());

        // 发射 validation 数据事件
        Map<String, Object> validationData = objectMapper.convertValue(report, new TypeReference<Map<String, Object>>() {});
        String validationConvId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString).orElse("unknown");
        emitEvent(StreamChunk.nodeData("validation", "validation", validationData, validationConvId));

        String nextNode = Boolean.TRUE.equals(report.getPassed()) ? "report" : "manager";
        return Map.of(
                TripPlanningStateKeys.VALIDATION_REPORT, report,
                TripPlanningStateKeys.CONTROL_NEXT_NODE, nextNode,
                TripPlanningStateKeys.OUTPUT_STATUS,
                Boolean.TRUE.equals(report.getPassed()) ? "validation_passed" : "validation_failed"
        );
    }

    private String buildContext(OverAllState state, Constraints c, BudgetPlan budget, List<DayPlan> itinerary) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("""
                目的地: %s
                天数: %d
                预算: %d 元
                同行人数: %d
                偏好: %s
                软约束: %s
                """,
                c.getDestination(), c.getDays(), c.getBudget(),
                c.getCompanions(), c.getPreferences(), c.getSoftRequirements()));

        if (budget != null) {
            sb.append(String.format("""
                    
                    预算详情:
                      总费用: %d 元
                      是否超支: %s
                      超出金额: %d 元
                      分项: %s
                    """,
                    budget.getTotalCost(),
                    Boolean.TRUE.equals(budget.getIsOverBudget()) ? "是" : "否",
                    budget.getOverageAmount(),
                    budget.getBreakdown()));
        } else {
            state.value(TripPlanningStateKeys.WORKER_BUDGET_RAW)
                    .ifPresent(raw -> sb.append("\n预算详情(原始):\n").append(raw).append("\n"));
        }

        if (itinerary != null && !itinerary.isEmpty()) {
            sb.append("\n每日行程:\n");
            for (DayPlan day : itinerary) {
                sb.append(String.format("  第%d天%n", day.getDayIndex()));
                if (day.getPois() != null) {
                    for (var poi : day.getPois()) {
                        sb.append(String.format("    - %s (%s)%n", poi.getName(), poi.getType()));
                    }
                }
            }
        } else {
            state.value(TripPlanningStateKeys.WORKER_ITINERARY_RAW)
                    .ifPresent(raw -> sb.append("\n每日行程(原始):\n").append(raw).append("\n"));
        }

        return sb.toString();
    }

    private ValidationReport parseValidation(String json) {
        try {
            var root = jsonExtractor.extract(json);
            if (root == null) return null;

            boolean passed = !root.hasNonNull("passed") || root.get("passed").asBoolean();

            List<ValidationReport.Failure> failures = new ArrayList<>();
            if (root.has("failures") && root.get("failures").isArray()) {
                for (var fNode : root.get("failures")) {
                    ValidationReport.Failure f = new ValidationReport.Failure();
                    f.setDimension(fNode.hasNonNull("dimension") ? fNode.get("dimension").asText() : "");
                    f.setRequirement(fNode.hasNonNull("requirement") ? fNode.get("requirement").asText() : "");
                    f.setReason(fNode.hasNonNull("reason") ? fNode.get("reason").asText() : "");
                    f.setSuggestion(fNode.hasNonNull("suggestion") ? fNode.get("suggestion").asText() : "");
                    failures.add(f);
                }
            }

            List<String> warnings = new ArrayList<>();
            if (root.has("warnings") && root.get("warnings").isArray()) {
                root.get("warnings").forEach(w -> warnings.add(w.asText()));
            }

            String summary = root.hasNonNull("summary") ? root.get("summary").asText() : "";

            return ValidationReport.builder()
                    .passed(passed)
                    .failures(failures)
                    .warnings(warnings)
                    .build();
        } catch (Exception e) {
            log.error("[ValidationAgent] 解析校验 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> mockPassed() {
        return Map.of(
                TripPlanningStateKeys.VALIDATION_REPORT,
                ValidationReport.builder().passed(true).failures(List.of()).warnings(List.of()).build(),
                TripPlanningStateKeys.CONTROL_NEXT_NODE, "report",
                TripPlanningStateKeys.OUTPUT_STATUS, "validation_passed"
        );
    }
}
