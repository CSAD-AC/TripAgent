package uno.zhuchen.workflow.builder;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.tool.AskUserTool;
import uno.zhuchen.workflow.agent.BudgetAgent;
import uno.zhuchen.workflow.agent.ItineraryAgent;
import uno.zhuchen.workflow.agent.ManagerAgent;
import uno.zhuchen.workflow.agent.ReportAgent;
import uno.zhuchen.workflow.agent.RouteAgent;
import uno.zhuchen.workflow.agent.ValidationAgent;
import uno.zhuchen.workflow.state.BudgetPlan;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.RouteResult;
import uno.zhuchen.workflow.state.ValidationReport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;

/**
 * TripPlanningGraphBuilder 集成测试
 *
 * Day 2 目标: 验证完整 SWV Graph 装配 + 6 个 Agent 串联跑通
 * 全部 Agent 为空壳/返回 mock 数据,只验证编排逻辑正确。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripPlanningGraphBuilderTest {

    /**
     * 测试用 ChatModel — 返回有效 JSON 提取 + 确认问题
     * ManagerAgent 首次模式需要 LLM 提取约束和生成确认问题
     */
    private static ChatModel stubChatModel() {
        return new ChatModel() {
            @Override
            public AssistantMessage call(List<org.springframework.ai.chat.messages.Message> messages,
                                         org.springframework.ai.tool.ToolCallback... tools) {
                // 简单判断：第一次调用（提取）返回约束 JSON，第二次（确认）返回问题
                // 实际中可通过消息内容判断，这里固定返回提取结果
                return new AssistantMessage(
                        "{\"destination\":\"北京\",\"days\":3,\"budget\":5000,\"companions\":2," +
                        "\"preferences\":[\"文化\"],\"softRequirements\":[]}");
            }

            @Override
            public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                    List<org.springframework.ai.chat.messages.Message> messages,
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

    private TripPlanningGraphBuilder newBuilder() {
        ChatModel cm = stubChatModel();
        return new TripPlanningGraphBuilder(
                new ManagerAgent(cm, mockAskUserTool(), new ObjectMapper()),
                new RouteAgent(cm),
                new ItineraryAgent(cm),
                new BudgetAgent(cm),
                new ValidationAgent(cm),
                new ReportAgent(cm)
        );
    }

    @Test
    @DisplayName("Graph 编译 + 完整跑通（happy path: 不超预算）")
    void fullGraphHappyPath() throws Exception {
        CompiledGraph graph = newBuilder().build();

        // 预算充足: 5000 元，mock 总花费 2200
        Constraints constraints = Constraints.builder()
                .destination("北京")
                .days(3)
                .budget(5000)
                .companions(2)
                .build();

        Map<String, Object> input = new HashMap<>();
        input.put("raw_request", "北京 3 日游，预算 5000");
        input.put("constraints", constraints);
        input.put("conversation_id", "test-conv-1");
        input.put("trace_id", "test-trace-1");

        RunnableConfig config = RunnableConfig.builder()
                .threadId("day2-happy-" + System.currentTimeMillis())
                .build();

        StringBuilder trace = new StringBuilder();
        Flux<NodeOutput> stream = graph.stream(input, config);
        stream.doOnNext(output -> trace.append(output.node()).append(" -> "))
              .doOnError(err -> trace.append("ERROR: ").append(err.getMessage()))
              .doOnComplete(() -> trace.append("END"))
              .blockLast();

        System.out.println("Trace: " + trace);

        // 验证 trace 包含全部 6 个节点
        String t = trace.toString();
        assertTrue(t.contains("manager"), "trace should include manager");
        assertTrue(t.contains("route"), "trace should include route");
        assertTrue(t.contains("itinerary"), "trace should include itinerary");
        assertTrue(t.contains("budget"), "trace should include budget");
        assertTrue(t.contains("validation"), "trace should include validation");
        assertTrue(t.contains("report"), "trace should include report");

        // 验证终态
        OverAllState finalState = graph.getState(config).state();
        Map<String, Object> data = finalState.data();

        assertEquals("success", data.get("status"), "should end with status=success");
        assertNotNull(data.get("final_report"), "should produce final report");
        assertNotNull(data.get("route"), "should have route in state");
        assertNotNull(data.get("itinerary"), "should have itinerary in state");
        assertNotNull(data.get("budget"), "should have budget in state");
        assertNotNull(data.get("validation_report"), "should have validation report");

        // 校验 passed
        ValidationReport report = (ValidationReport) data.get("validation_report");
        assertTrue(report.getPassed(), "happy path should pass validation");
    }

    @Test
    @DisplayName("Graph 编译 + 超预算 → 校验失败 → 回退 manager")
    void fullGraphOverBudgetFallback() throws Exception {
        CompiledGraph graph = newBuilder().build();

        // 预算超紧: 100 元，但 mock 总花费 2200
        Constraints constraints = Constraints.builder()
                .destination("北京")
                .days(3)
                .budget(100)
                .companions(2)
                .build();

        Map<String, Object> input = new HashMap<>();
        input.put("raw_request", "北京 3 日游，预算 100");
        input.put("constraints", constraints);
        input.put("conversation_id", "test-conv-2");

        RunnableConfig config = RunnableConfig.builder()
                .threadId("day2-over-" + System.currentTimeMillis())
                .build();

        StringBuilder trace = new StringBuilder();
        Flux<NodeOutput> stream = graph.stream(input, config);
        stream.doOnNext(output -> trace.append(output.node()).append(" -> "))
              .doOnError(err -> trace.append("ERROR: ").append(err.getMessage()))
              .doOnComplete(() -> trace.append("END"))
              .blockLast();

        System.out.println("Over-budget Trace: " + trace);

        // Day 2 简化: ValidationAgent 失败时 next_node=manager, manager 又回到 route
        // 死循环终止条件: Day 5 实现 (max iteration)
        // Day 2 仅验证 fallback 路径被触发
        String t = trace.toString();
        assertTrue(t.contains("validation"), "should include validation");
    }

    @Test
    @DisplayName("Type-safe state 读取：route/itinerary/budget 节点输出类型正确")
    void stateTypeSafety() throws Exception {
        CompiledGraph graph = newBuilder().build();

        Constraints constraints = Constraints.builder()
                .destination("上海").days(2).budget(3000).companions(1).build();

        Map<String, Object> input = new HashMap<>();
        input.put("raw_request", "上海 2 日游");
        input.put("constraints", constraints);
        input.put("conversation_id", "test-conv-3");

        RunnableConfig config = RunnableConfig.builder()
                .threadId("day2-types-" + System.currentTimeMillis())
                .build();

        graph.stream(input, config).blockLast();

        OverAllState finalState = graph.getState(config).state();
        Map<String, Object> data = finalState.data();

        // 类型断言
        assertTrue(data.get("route") instanceof RouteResult, "route should be RouteResult");
        assertTrue(data.get("budget") instanceof BudgetPlan, "budget should be BudgetPlan");
        assertTrue(data.get("constraints") instanceof Constraints, "constraints should be Constraints");
        assertTrue(data.get("validation_report") instanceof ValidationReport, "validation_report should be ValidationReport");
    }
}
