package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.tool.AskUserTool;
import uno.zhuchen.agent.tool.AskUserToolCallback;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.state.ValidationReport;
import uno.zhuchen.workflow.util.JsonExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主管 Agent — Supervisor 层
 *
 * 两种执行模式（通过 state[validation_report] 是否存在区分）:
 * - 首次模式: 提取约束 + softRequirements + 调用 AskUserTool 复述确认
 * - 回退模式: 接收 ValidationReport, LLM 决策 retry/ask_user/give_up
 *
 * 状态转移（state[next_node]）:
 * - 首次: next_node = "confirmed" (用户确认后进 worker_group)
 * - 首次用户否认: next_node = "first" (重新提取)
 * - 回退 retry: next_node = 对应 worker (如 "itinerary" 部分重跑)
 * - 回退 give_up: next_node = "report" (强制通过)
 * - 回退 ask_user: next_node = "first" (再次反问用户)
 *
 * Day 3: 完整实现 + AskUserTool 集成
 */
@Component
public class ManagerAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(ManagerAgent.class);

    /** 首次模式 Prompt — LLM 自主驱动需求澄清(可调用 askUser 工具) */
    private static final String SYSTEM_PROMPT_EXTRACT = """
            你是旅游规划助手，负责梳理用户的需求并提取结构化约束。

            你可用的工具：
            - askUser(question, options, allowCustom): 向用户追问。当信息不足时调用此工具。问题要自然，选项要清晰。

            === 工作方式 ===
            你自主决定何时提问、何时输出最终结果。
            典型流程：
            1. 先看用户需求能提取出哪些字段
            2. 如果缺少必填字段，调用 askUser 向用户追问
            3. 根据用户回答更新约束
            4. 如果仍然缺少信息，继续追问
            5. 当所有必填字段都明确后，输出最终 JSON

            效率要求：一次问完所有必要信息，不要只问一个字段。比如缺预算和天数时，一次问清楚。

            === 提取规则 ===
            destination: 目的地城市（必填）
            days: 天数（必填，正整数）
            budget: 预算（必填，正整数，单位元）
            companions: 同行人数（必填，正整数，包含用户本人）
            preferences: 偏好列表，如 "自然"、"文化"、"美食"
            softRequirements: 软约束列表，用户提到的个性化需求

            === 输出要求 ===
            所有必填字段都明确后，直接输出纯 JSON，不要 markdown 代码块，不要 ```json 标记，不要任何解释文字。
            softRequirements 只放用户明确表达的个性化需求。
            示例输出（纯 JSON，无其他文字）：
            {"destination":"北京","days":3,"budget":5000,"companions":2,"preferences":["文化","美食"],"softRequirements":["中途要去游乐园"]}
            """;

    /** 首次模式 Prompt — 生成复述确认问题 */
    private static final String SYSTEM_PROMPT_CONFIRM = """
            你是旅游规划助手，需要向用户复述你对需求的理解，让用户确认。

            根据已提取的约束，生成一个简洁的确认问题。
            问题要列举：目的地、天数、预算、人数、软约束。
            用户可选择"全部正确"或"需要修改"。

            直接输出纯 JSON，不要 markdown 代码块，不要 ```json 标记，不要任何解释文字：
            {"question":"你希望去 XX 玩 X 天，预算 X 元，X 人同行。对吗？","summary":"北京 3 日游，预算 5000，2 人，含中途去游乐园"}
            """;

    /** 回退模式 Prompt — 决策 retry/ask_user/give_up */
    private static final String SYSTEM_PROMPT_DECIDE = """
            你是旅游规划助手主管。Worker 团队产出的方案未通过校验。
            你需要根据失败原因，决策下一步：
            - retry: 让对应 Worker 重做（需要指明重跑哪个 worker）
            - ask_user: 反问用户获取更多信息
            - give_up: 当前轮次放弃，强制进入报告

            决策原则：
            1. 失败原因是预算超支 → 优先 retry 行程编排（减少景点）或住宿（降低标准）
            2. 失败原因是软约束（如"中途要去游乐园"未满足）→ retry 行程编排
            3. 失败原因连续 2 次无法解决 → ask_user 或 give_up
            4. 用户已在当前轮确认过 → give_up（避免无限循环）

            直接输出纯 JSON，不要 markdown 代码块，不要 ```json 标记，不要任何解释文字：
            {"decision":"retry","targetWorker":"itinerary","retryHint":"加入环球影城,替换王府井","reason":"软约束'中途要去游乐园'未满足"}
            """;

    private final AskUserTool askUserTool;
    private final AskUserToolCallback askUserToolCallback;
    private final ObjectMapper objectMapper;
    private final JsonExtractor jsonExtractor;

    public ManagerAgent(ChatModel chatModel, AskUserTool askUserTool,
                        AskUserToolCallback askUserToolCallback, ObjectMapper objectMapper) {
        super("ManagerAgent", chatModel, null);
        this.askUserTool = askUserTool;
        this.askUserToolCallback = askUserToolCallback;
        this.objectMapper = objectMapper;
        this.jsonExtractor = new JsonExtractor(objectMapper);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        // 区分模式：state 中有 validation_report 则是回退模式
        if (findValidationReport(state).isPresent()) {
            return handleFallback(state);
        }
        return handleFirstTime(state);
    }

    /** 给缺失字段填充合理的默认值 */
    private void applyDefaults(Constraints c) {
        if (c.getBudget() == null) c.setBudget(3000);
        if (c.getCompanions() == null) c.setCompanions(1);
        if (c.getDays() == null) c.setDays(3);
        if (c.getDestination() == null || c.getDestination().isBlank()) c.setDestination("北京");
    }

    /**
     * 首次模式：LLM 自主驱动澄清 + 提取约束。
     *
     * <p>LLM 在此过程中可自主调用 {@code askUser} 工具向用户提问，
     * 直到信息足够后输出完整的约束 JSON。Java 只负责执行工具调用并将结果喂回 LLM。
     */
    private Map<String, Object> handleFirstTime(OverAllState state) {
        String rawRequest = state.value(TripPlanningStateKeys.INPUT_RAW_REQUEST)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalStateException("raw_request missing"));

        String conversationId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalStateException("conversation_id missing"));

        log.info("[ManagerAgent] 首次模式: LLM 自主驱动澄清, rawRequest={}", rawRequest);

        // 1. LLM 自主驱动提取+反问循环(最多 15 轮工具调用)
        String extractJson = callLLMWithTools(
                SYSTEM_PROMPT_EXTRACT,
                rawRequest + "\n(conversationId=" + conversationId + ")",
                new ToolCallback[]{askUserToolCallback},
                15
        );
        Constraints constraints = parseConstraints(extractJson);
        log.info("[ManagerAgent] LLM 自主提取结果: destination={}, days={}, budget={}, companions={}",
                constraints.getDestination(), constraints.getDays(),
                constraints.getBudget(), constraints.getCompanions());

        // 2. 兜底: LLM 仍未提取完整时补默认值
        if (!constraints.isComplete()) {
            log.warn("[ManagerAgent] LLM 最终输出仍不完整: {}, 应用默认值", constraints.missingRequiredFields());
            applyDefaults(constraints);
        }

        // 3. 生成复述确认问题 + 等待用户确认
        String confirmJson = callLLM(SYSTEM_PROMPT_CONFIRM,
                "约束:\n" + extractJson);
        QuestionPayload confirm = parseConfirmQuestion(confirmJson);

        String userAnswer;
        try {
            AskUserTool.setConversationId(conversationId);
            userAnswer = askUserTool.askUser(
                    confirm.question(),
                    List.of(
                            new AskUserTool.Option("全部正确", "confirmed"),
                            new AskUserTool.Option("需要修改", "modify")
                    ),
                    true
            );
        } finally {
            AskUserTool.clearConversationId();
        }

        boolean confirmed = userAnswer.contains("confirmed") || userAnswer.contains("正确");
        if (confirmed) {
            log.info("[ManagerAgent] 用户确认约束, 进入 worker_group");
            return Map.of(
                    TripPlanningStateKeys.CONSTRAINTS, constraints,
                    TripPlanningStateKeys.CONTROL_NEXT_NODE, "worker_group",
                    TripPlanningStateKeys.OUTPUT_STATUS, "constraints_confirmed"
            );
        } else {
            log.info("[ManagerAgent] 用户需修改约束, 重新提取");
            return Map.of(
                    TripPlanningStateKeys.CONSTRAINTS, constraints,
                    TripPlanningStateKeys.CONTROL_NEXT_NODE, "first",
                    TripPlanningStateKeys.OUTPUT_STATUS, "constraints_rejected"
            );
        }
    }

    /**
     * 回退模式：接收 ValidationReport, LLM 决策
     */
    private Map<String, Object> handleFallback(OverAllState state) {
        Constraints constraints = findConstraints(state).orElse(null);
        ValidationReport report = findValidationReport(state).orElseThrow();
        int iteration = state.value(TripPlanningStateKeys.CONTROL_ITERATION_COUNT)
                .map(v -> ((Number) v).intValue())
                .orElse(0);
        int newIteration = iteration + 1;

        log.info("[ManagerAgent] 回退模式: iteration={}→{}, failures={}",
                iteration, newIteration, report.getFailures().size());

        // 达上限 → 强制给报告
        if (newIteration >= 2) {
            log.warn("[ManagerAgent] 达到最大迭代次数, 强制给报告");
            return Map.of(
                    TripPlanningStateKeys.CONTROL_ITERATION_COUNT, newIteration,
                    TripPlanningStateKeys.CONTROL_NEXT_NODE, "report",
                    TripPlanningStateKeys.OUTPUT_STATUS, "force_passed"
            );
        }

        // LLM 决策
        String decideJson = callLLM(SYSTEM_PROMPT_DECIDE,
                "约束:\n" + constraints + "\n失败原因:\n" + report.getFailures());
        Decision decision = parseDecision(decideJson);
        log.info("[ManagerAgent] LLM 决策: {} (target={}, reason={})",
                decision.decision(), decision.targetWorker(), decision.reason());

        String nextNode = switch (decision.decision()) {
            case "retry" -> decision.targetWorker() != null ? decision.targetWorker() : "itinerary";
            case "ask_user" -> "first";
            case "give_up" -> "report";
            default -> "report";
        };

        // 把 retryHint 注入 state 供目标 worker 看到
        Map<String, Object> result = new HashMap<>();
        result.put(TripPlanningStateKeys.CONTROL_ITERATION_COUNT, newIteration);
        result.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, nextNode);
        result.put(TripPlanningStateKeys.OUTPUT_STATUS, "retry_" + decision.decision());
        if (decision.retryHint() != null) {
            result.put(TripPlanningStateKeys.CONTROL_WARNINGS, List.of(decision.retryHint()));
        }
        return result;
    }

    // ============ JSON 解析辅助 ============

    private Constraints parseConstraints(String json) {
        try {
            var root = jsonExtractor.extract(json);
            if (root == null) return new Constraints();

            Constraints c = new Constraints();
            if (root.hasNonNull("destination")) {
                c.setDestination(root.get("destination").asText());
            }
            if (root.hasNonNull("days")) {
                c.setDays(root.get("days").asInt());
            }
            if (root.hasNonNull("budget")) {
                c.setBudget(root.get("budget").asInt());
            }
            if (root.hasNonNull("companions")) {
                c.setCompanions(root.get("companions").asInt());
            }
            List<String> prefs = new ArrayList<>();
            if (root.has("preferences") && root.get("preferences").isArray()) {
                root.get("preferences").forEach(n -> prefs.add(n.asText()));
            }
            c.setPreferences(prefs);
            List<String> softs = new ArrayList<>();
            if (root.has("softRequirements") && root.get("softRequirements").isArray()) {
                root.get("softRequirements").forEach(n -> softs.add(n.asText()));
            }
            c.setSoftRequirements(softs);
            return c;
        } catch (Exception e) {
            log.error("[ManagerAgent] 解析约束 JSON 失败: {}, raw={}", e.getMessage(), json);
            return new Constraints();
        }
    }

    private QuestionPayload parseConfirmQuestion(String json) {
        try {
            var root = jsonExtractor.extract(json);
            if (root == null) return new QuestionPayload("请确认你的需求", "");

            String question = root.hasNonNull("question") ? root.get("question").asText() : "请确认需求";
            String summary = root.hasNonNull("summary") ? root.get("summary").asText() : "";
            return new QuestionPayload(question, summary);
        } catch (Exception e) {
            return new QuestionPayload("请确认你的需求", "");
        }
    }

    private Decision parseDecision(String json) {
        try {
            var root = jsonExtractor.extract(json);
            if (root == null) return new Decision("give_up", null, null, "JSON 解析失败");

            return new Decision(
                    root.hasNonNull("decision") ? root.get("decision").asText() : "give_up",
                    root.hasNonNull("targetWorker") && !root.get("targetWorker").isNull()
                            ? root.get("targetWorker").asText() : null,
                    root.hasNonNull("retryHint") && !root.get("retryHint").isNull()
                            ? root.get("retryHint").asText() : null,
                    root.hasNonNull("reason") && !root.get("reason").isNull()
                            ? root.get("reason").asText() : ""
            );
        } catch (Exception e) {
            return new Decision("give_up", null, null, "JSON 解析失败");
        }
    }

    /** 内部 record: 复述确认问题 */
    private record QuestionPayload(String question, String summary) {}

    /** 内部 record: LLM 决策 */
    private record Decision(String decision, String targetWorker, String retryHint, String reason) {}
}
