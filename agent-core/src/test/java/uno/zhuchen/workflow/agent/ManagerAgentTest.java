package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.tool.AskUserTool;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.state.ValidationReport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;

/**
 * ManagerAgent 单元测试
 *
 * Day 3 目标: 验证两种模式（首次 / 回退）的核心逻辑
 * - 首次: LLM 提取约束 JSON + AskUserTool 复述确认
 * - 回退: LLM 决策 retry/give_up/ask_user
 *
 * 不启动 Spring 上下文，纯手工 stub + Mockito，避免 MCP 客户端干扰。
 */
class ManagerAgentTest {

    private ObjectMapper objectMapper;
    private ManagerAgent managerAgent;

    /**
     * 构造按顺序返回响应的 ChatModel stub
     *
     * @param responses 多个 LLM 响应，按调用顺序返回
     */
    private static ChatModel stubChatModel(String... responses) {
        AtomicInteger idx = new AtomicInteger(0);
        return new ChatModel() {
            @Override
            public AssistantMessage call(List<Message> messages,
                                         org.springframework.ai.tool.ToolCallback... tools) {
                int i = Math.min(idx.getAndIncrement(), responses.length - 1);
                return new AssistantMessage(responses[i]);
            }

            @Override
            public Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                    List<Message> messages,
                    org.springframework.ai.tool.ToolCallback... tools) {
                return Flux.empty();
            }
        };
    }

    /** Mock AskUserTool — 所有调用都返回 "confirmed" */
    private static AskUserTool mockAskUserTool() {
        AskUserTool mock = mock(AskUserTool.class);
        when(mock.askUser(any(), anyList(), anyBoolean())).thenReturn("用户回答: confirmed");
        return mock;
    }

    /**
     * 构造一个最小可用的 OverAllState（避免 Spring 上下文依赖）
     */
    private OverAllState newState(Map<String, Object> data) {
        OverAllState state = new OverAllState(data);
        Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> strategies = new HashMap<>();
        strategies.put(TripPlanningStateKeys.INPUT_RAW_REQUEST, new ReplaceStrategy());
        strategies.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, new ReplaceStrategy());
        strategies.put(TripPlanningStateKeys.CONSTRAINTS, new ReplaceStrategy());
        strategies.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, new ReplaceStrategy());
        strategies.put(TripPlanningStateKeys.CONTROL_ITERATION_COUNT, new ReplaceStrategy());
        strategies.put(TripPlanningStateKeys.CONTROL_WARNINGS, new AppendStrategy());
        strategies.put(TripPlanningStateKeys.VALIDATION_REPORT, new ReplaceStrategy());
        state.registerKeyAndStrategy(strategies);
        return state;
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        managerAgent = new ManagerAgent(stubChatModel(), mockAskUserTool(), objectMapper);
    }

    // ============ 首次模式 ============

    @Test
    @DisplayName("首次模式: LLM 提取 + 用户确认 → next_node=worker_group")
    void firstTimeUserConfirmed() throws Exception {
        // 用特定 JSON 重新构建（不同 stub 序列）
        String extractJson = """
                {"destination":"北京","days":3,"budget":5000,"companions":2,
                 "preferences":["文化"],"softRequirements":["中途去游乐园"]}
                """;
        String confirmJson = """
                {"question":"去北京3天,预算5000,2人,中途去游乐园,对吗?",
                 "summary":"北京3日游,含游乐园"}
                """;
        managerAgent = new ManagerAgent(
                stubChatModel(extractJson, confirmJson),
                mockAskUserTool(),
                objectMapper);

        Map<String, Object> input = new HashMap<>();
        input.put(TripPlanningStateKeys.INPUT_RAW_REQUEST, "北京3日游,预算5000,2人,中途去游乐园");
        input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, "conv-1");
        OverAllState state = newState(input);

        Map<String, Object> result = managerAgent.apply(state);

        Constraints c = (Constraints) result.get(TripPlanningStateKeys.CONSTRAINTS);
        assertNotNull(c);
        assertEquals("北京", c.getDestination());
        assertEquals(3, c.getDays());
        assertEquals(5000, c.getBudget());
        assertEquals(2, c.getCompanions());
        assertEquals(List.of("中途去游乐园"), c.getSoftRequirements());

        assertEquals("worker_group", result.get(TripPlanningStateKeys.CONTROL_NEXT_NODE));
        assertEquals("constraints_confirmed", result.get(TripPlanningStateKeys.OUTPUT_STATUS));
    }

    @Test
    @DisplayName("首次模式: 用户否认 → next_node=first（重提取）")
    void firstTimeUserRejected() throws Exception {
        AskUserTool mockAsk = mock(AskUserTool.class);
        when(mockAsk.askUser(any(), anyList(), anyBoolean())).thenReturn("用户回答: modify");

        String extractJson = """
                {"destination":"上海","days":2,"budget":3000,"companions":1}
                """;
        String confirmJson = """
                {"question":"上海2日游,对吗?","summary":""}
                """;
        managerAgent = new ManagerAgent(
                stubChatModel(extractJson, confirmJson),
                mockAsk,
                objectMapper);

        Map<String, Object> input = new HashMap<>();
        input.put(TripPlanningStateKeys.INPUT_RAW_REQUEST, "上海2日游");
        input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, "conv-2");
        OverAllState state = newState(input);

        Map<String, Object> result = managerAgent.apply(state);

        assertEquals("first", result.get(TripPlanningStateKeys.CONTROL_NEXT_NODE));
        assertEquals("constraints_rejected", result.get(TripPlanningStateKeys.OUTPUT_STATUS));
    }

    @Test
    @DisplayName("首次模式: 必填字段缺失 → 反问 + 部分约束继续")
    void firstTimeMissingFields() throws Exception {
        String extractJson = """
                {"destination":"成都","preferences":["美食"]}
                """;
        String confirmJson = """
                {"question":"请确认","summary":""}
                """;
        managerAgent = new ManagerAgent(
                stubChatModel(extractJson, confirmJson),
                mockAskUserTool(),
                objectMapper);

        Map<String, Object> input = new HashMap<>();
        input.put(TripPlanningStateKeys.INPUT_RAW_REQUEST, "想去成都");
        input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, "conv-3");
        OverAllState state = newState(input);

        Map<String, Object> result = managerAgent.apply(state);

        Constraints c = (Constraints) result.get(TripPlanningStateKeys.CONSTRAINTS);
        assertNotNull(c);
        assertEquals("成都", c.getDestination());
        assertEquals("worker_group", result.get(TripPlanningStateKeys.CONTROL_NEXT_NODE));
    }

    // ============ 回退模式 ============

    @Test
    @DisplayName("回退模式: LLM 决策 retry → next_node=目标 worker")
    void fallbackRetry() throws Exception {
        Constraints constraints = Constraints.builder()
                .destination("北京").days(3).budget(5000).companions(2)
                .softRequirements(List.of("中途去游乐园"))
                .build();
        ValidationReport report = ValidationReport.builder()
                .passed(false)
                .failures(List.of(ValidationReport.Failure.builder()
                        .dimension("soft.requirement")
                        .requirement("中途去游乐园")
                        .reason("行程未含游乐园")
                        .build()))
                .build();
        String decideJson = """
                {"decision":"retry","targetWorker":"itinerary",
                 "retryHint":"加入环球影城,替换王府井",
                 "reason":"软约束未满足,重排行程"}
                """;
        managerAgent = new ManagerAgent(
                stubChatModel(decideJson),
                mockAskUserTool(),
                objectMapper);

        Map<String, Object> input = new HashMap<>();
        input.put(TripPlanningStateKeys.CONSTRAINTS, constraints);
        input.put(TripPlanningStateKeys.VALIDATION_REPORT, report);
        input.put(TripPlanningStateKeys.CONTROL_ITERATION_COUNT, 0);
        input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, "conv-fallback-1");
        OverAllState state = newState(input);

        Map<String, Object> result = managerAgent.apply(state);

        assertEquals("itinerary", result.get(TripPlanningStateKeys.CONTROL_NEXT_NODE));
        assertEquals(1, result.get(TripPlanningStateKeys.CONTROL_ITERATION_COUNT));
        assertEquals("retry_retry", result.get(TripPlanningStateKeys.OUTPUT_STATUS));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get(TripPlanningStateKeys.CONTROL_WARNINGS);
        assertNotNull(warnings);
        assertTrue(warnings.contains("加入环球影城,替换王府井"));
    }

    @Test
    @DisplayName("回退模式: 达到 max iteration (>=2) → 强制 give_up → report")
    void fallbackMaxIteration() throws Exception {
        Constraints constraints = Constraints.builder()
                .destination("北京").days(3).budget(5000).companions(2).build();
        ValidationReport report = ValidationReport.builder().passed(false).build();

        Map<String, Object> input = new HashMap<>();
        input.put(TripPlanningStateKeys.CONSTRAINTS, constraints);
        input.put(TripPlanningStateKeys.VALIDATION_REPORT, report);
        input.put(TripPlanningStateKeys.CONTROL_ITERATION_COUNT, 1);
        input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, "conv-max");
        OverAllState state = newState(input);

        Map<String, Object> result = managerAgent.apply(state);

        assertEquals("report", result.get(TripPlanningStateKeys.CONTROL_NEXT_NODE));
        assertEquals(2, result.get(TripPlanningStateKeys.CONTROL_ITERATION_COUNT));
        assertEquals("force_passed", result.get(TripPlanningStateKeys.OUTPUT_STATUS));
    }

    @Test
    @DisplayName("回退模式: LLM 决策 give_up → report")
    void fallbackGiveUp() throws Exception {
        Constraints constraints = Constraints.builder()
                .destination("北京").days(3).budget(5000).companions(2).build();
        ValidationReport report = ValidationReport.builder().passed(false).build();
        String decideJson = """
                {"decision":"give_up","reason":"无法在预算内满足所有需求"}
                """;
        managerAgent = new ManagerAgent(
                stubChatModel(decideJson),
                mockAskUserTool(),
                objectMapper);

        Map<String, Object> input = new HashMap<>();
        input.put(TripPlanningStateKeys.CONSTRAINTS, constraints);
        input.put(TripPlanningStateKeys.VALIDATION_REPORT, report);
        input.put(TripPlanningStateKeys.CONTROL_ITERATION_COUNT, 0);
        input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, "conv-giveup");
        OverAllState state = newState(input);

        Map<String, Object> result = managerAgent.apply(state);

        assertEquals("report", result.get(TripPlanningStateKeys.CONTROL_NEXT_NODE));
    }

    @Test
    @DisplayName("回退模式: LLM 决策 ask_user → first（重提取）")
    void fallbackAskUser() throws Exception {
        Constraints constraints = Constraints.builder()
                .destination("北京").days(3).budget(5000).companions(2).build();
        ValidationReport report = ValidationReport.builder().passed(false).build();
        String decideJson = """
                {"decision":"ask_user","reason":"预算与需求矛盾,需用户决策"}
                """;
        managerAgent = new ManagerAgent(
                stubChatModel(decideJson),
                mockAskUserTool(),
                objectMapper);

        Map<String, Object> input = new HashMap<>();
        input.put(TripPlanningStateKeys.CONSTRAINTS, constraints);
        input.put(TripPlanningStateKeys.VALIDATION_REPORT, report);
        input.put(TripPlanningStateKeys.CONTROL_ITERATION_COUNT, 0);
        input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, "conv-ask");
        OverAllState state = newState(input);

        Map<String, Object> result = managerAgent.apply(state);

        assertEquals("first", result.get(TripPlanningStateKeys.CONTROL_NEXT_NODE));
    }
}
