package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.tool.AskUserTool;
import uno.zhuchen.agent.tool.ToolRegistry;
import uno.zhuchen.workflow.state.BudgetPlan;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.DayPlan;
import uno.zhuchen.workflow.state.RouteResult;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.state.ValidationReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Agent 抽象基类
 *
 * 所有 Phase 3 Agent 都继承本类，统一实现以下职责:
 * - 实现 NodeAction 接口（Graph 框架硬性要求）
 * - 提供类型安全的 state 读写辅助方法（消除重复强转）
 * - 模板方法 apply() 统一处理日志 + 异常，子类只关心 execute()
 * - 持有 ChatModel 引用（复用 uno.zhuchen.agent.llm.ChatModel，复用 Phase 1 沉淀）
 *
 * 为什么不新建 LLMService 接口:
 * - uno.zhuchen.agent.llm.ChatModel 已是成熟抽象（Phase 1 验证过）
 * - workflow 单向依赖 agent，直接复用避免重复抽象
 * - callLLM(system, user) helper 已足够满足 Agent 调用需求
 *
 * 子类只需实现 {@link #doExecute(OverAllState)},返回 state 增量。
 */
public abstract class BaseAgent implements NodeAction {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** MCP 工具注册表（Worker Agent 用于调路线/POI/天气） */
    protected final ToolRegistry toolRegistry;

    /** LLM 调用抽象（直接复用 agent 包的 ChatModel） */
    protected final ChatModel chatModel;

    /** Agent 名称（用于日志和 Graph 节点 ID） */
    protected final String agentName;

    // ============ Graph 流事件发射器 ============

    /**
     * 线程局部的事件发射器 — GraphStreamRunner 在单个 boundedElastic 线程上串行执行，
     * 该线程调用每个 Agent 的 apply()，在此线程上 set 的 emitter 在同一线程的 Agent 内可被读取。
     */
    private static final ThreadLocal<Consumer<StreamChunk>> eventEmitterHolder = new ThreadLocal<>();

    /**
     * 设置当前线程的事件发射器（由 GraphStreamRunner 在执行 Graph 前调用）
     */
    public static void setEventEmitterForThread(Consumer<StreamChunk> emitter) {
        eventEmitterHolder.set(emitter);
    }

    /**
     * 清除当前线程的事件发射器（由 GraphStreamRunner 在 Graph 执行完毕后调用）
     */
    public static void clearEventEmitterForThread() {
        eventEmitterHolder.remove();
    }

    /**
     * 发射 Graph 流事件 — 子类可通过此方法发送 node_data / node_progress 等事件
     */
    protected void emitEvent(StreamChunk event) {
        Consumer<StreamChunk> emitter = eventEmitterHolder.get();
        if (emitter != null) {
            emitter.accept(event);
        }
    }

    protected BaseAgent(String agentName, ChatModel chatModel, ToolRegistry toolRegistry) {
        this.agentName = agentName;
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 模板方法 — Graph 框架调用入口
     *
     * 子类不应重写本方法，应重写 doExecute()。
     */
    @Override
    public final Map<String, Object> apply(OverAllState state) throws Exception {
        long start = System.currentTimeMillis();
        log.debug("[{}] apply() 入口, conversationId={}, iterationCount={}",
                agentName,
                state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID).orElse("unknown"),
                state.value(TripPlanningStateKeys.CONTROL_ITERATION_COUNT).orElse(0));

        try {
            Map<String, Object> result = doExecute(state);
            long duration = System.currentTimeMillis() - start;
            log.debug("[{}] doExecute 完成, 耗时 {}ms, 输出 keys={}",
                    agentName, duration, result.keySet());
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[{}] doExecute 失败, 耗时 {}ms, error={}",
                    agentName, duration, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 业务执行入口 — 子类实现
     *
     * @param state 共享状态
     * @return state 增量（被 KeyStrategy 合并）
     */
    protected abstract Map<String, Object> doExecute(OverAllState state) throws Exception;

    /**
     * 便捷 LLM 调用 — 适配器方法
     *
     * 把 (systemPrompt, userPrompt) 包装成 Message 列表调用 ChatModel。
     * 多数 Agent 只需要文本回复，不需要工具回调，所以不暴露 tools 参数。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 文本回复
     */
    protected String callLLM(String systemPrompt, String userPrompt) {
        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        );
        // 显式传空 vararg,便于 mockito 严格模式 stub
        return chatModel.call(messages, new org.springframework.ai.tool.ToolCallback[0]).getText();
    }

    /**
     * LLM 带工具循环调用 — LLM 可自主调用 askUser 等工具,Java 只负责执行并回传结果。
     *
     * <p>LLM 自主决定何时提问、何时输出最终答案,使需求澄清过程完全由 LLM 驱动。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户消息
     * @param tools        可用工具
     * @param maxRounds    最大工具调用轮次
     * @return LLM 最终输出的文本
     */
    protected String callLLMWithTools(String systemPrompt, String userPrompt,
                                       org.springframework.ai.tool.ToolCallback[] tools, int maxRounds) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        String convId = extractConversationId(userPrompt);

        emitEvent(StreamChunk.nodeProgress(agentName, "thinking", "开始分析...", convId));

        for (int round = 0; round < maxRounds; round++) {
            AssistantMessage response = chatModel.call(messages, tools);
            messages.add(response);

            if (!response.hasToolCalls()) {
                String text = response.getText();
                log.debug("[{}] LLM 输出最终结果, text长度={}", agentName,
                        text != null ? text.length() : 0);
                emitEvent(StreamChunk.nodeProgress(agentName, "thinking",
                        "分析完成", convId));
                return text != null ? text : "";
            }

            log.debug("[{}] LLM 请求调用 {} 个工具: {}", agentName,
                    response.getToolCalls().size(),
                    response.getToolCalls().stream()
                            .map(tc -> tc.name() + "(" + truncate(tc.arguments(), 80) + ")")
                            .toList());

            for (AssistantMessage.ToolCall tc : response.getToolCalls()) {
                org.springframework.ai.tool.ToolCallback tool = findTool(tools, tc.name());
                if (tool == null) {
                    log.warn("[{}] 未知工具: {}, 跳过", agentName, tc.name());
                    continue;
                }

                // 发射工具调用事件 (Graph 流)
                emitEvent(StreamChunk.nodeProgress(agentName, "tool_call",
                        tc.name() + "(" + truncate(tc.arguments(), 60) + ")", convId));

                try {
                    if ("askUser".equals(tc.name())) {
                        AskUserTool.setConversationId(convId);
                    }
                    long t0 = System.currentTimeMillis();
                    String result = tool.call(tc.arguments());
                    long elapsed = System.currentTimeMillis() - t0;
                    log.debug("[{}] 工具 {} 返回 ({}ms): {}",
                            agentName, tc.name(), elapsed, truncate(result, 120));

                    emitEvent(StreamChunk.nodeProgress(agentName, "tool_result",
                            tc.name() + " 返回 (" + elapsed + "ms)", convId));

                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    tc.id(), tc.name(), result)))
                            .build());
                } catch (Exception e) {
                    log.warn("[{}] 工具 {} 异常 ({}): {}", agentName, tc.name(),
                            e.getClass().getSimpleName(), e.getMessage());
                    emitEvent(StreamChunk.nodeProgress(agentName, "tool_result",
                            tc.name() + " 异常: " + e.getMessage(), convId));
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    tc.id(), tc.name(), "失败: " + e.getMessage())))
                            .build());
                } finally {
                    AskUserTool.clearConversationId();
                }
            }

            emitEvent(StreamChunk.nodeProgress(agentName, "thinking",
                    "分析工具返回结果...", convId));
        }
        log.warn("[{}] 达到最大工具调用轮次 {}, 返回空", agentName, maxRounds);
        emitEvent(StreamChunk.nodeProgress(agentName, "thinking",
                "达到最大轮次，强制结束", convId));
        return "";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private org.springframework.ai.tool.ToolCallback findTool(
            org.springframework.ai.tool.ToolCallback[] tools, String name) {
        for (var t : tools) {
            if (t.getToolDefinition().name().equals(name)) return t;
        }
        return null;
    }

    private String extractConversationId(String text) {
        var m = java.util.regex.Pattern.compile("conversationId=([a-f0-9-]+)").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 调用 MCP 工具
     *
     * @param toolName 工具名（如 "amapDrivingRoute"）
     * @param argsJson JSON 字符串参数
     * @return 工具返回的字符串结果
     */
    protected String callMcpTool(String toolName, String argsJson) {
        if (toolRegistry == null) {
            throw new IllegalStateException("[" + agentName + "] ToolRegistry 未注入");
        }
        org.springframework.ai.tool.ToolCallback tool = toolRegistry.getByName(toolName);
        if (tool == null) {
            throw new IllegalStateException("[" + agentName + "] MCP 工具不存在: " + toolName);
        }
        return tool.call(argsJson);
    }

    /** 读取 Constraints（必填） */
    protected Constraints getConstraints(OverAllState state) {
        return getTyped(state, TripPlanningStateKeys.CONSTRAINTS, Constraints.class);
    }

    /** 读取 Constraints（可选） */
    protected Optional<Constraints> findConstraints(OverAllState state) {
        return findTyped(state, TripPlanningStateKeys.CONSTRAINTS, Constraints.class);
    }

    /** 读取 RouteResult（可选） */
    protected Optional<RouteResult> findRoute(OverAllState state) {
        return findTyped(state, TripPlanningStateKeys.WORKER_ROUTE, RouteResult.class);
    }

    /** 读取 List<DayPlan>（可选） */
    @SuppressWarnings("unchecked")
    protected Optional<List<DayPlan>> findItinerary(OverAllState state) {
        return state.value(TripPlanningStateKeys.WORKER_ITINERARY)
                .map(v -> (List<DayPlan>) v);
    }

    /** 读取 BudgetPlan（可选） */
    protected Optional<BudgetPlan> findBudget(OverAllState state) {
        return findTyped(state, TripPlanningStateKeys.WORKER_BUDGET, BudgetPlan.class);
    }

    /** 读取 ValidationReport（可选） */
    protected Optional<ValidationReport> findValidationReport(OverAllState state) {
        return findTyped(state, TripPlanningStateKeys.VALIDATION_REPORT, ValidationReport.class);
    }

    /**
     * 通用类型安全读取（必填）
     */
    protected <T> T getTyped(OverAllState state, String key, Class<T> type) {
        return findTyped(state, key, type).orElseThrow(() ->
                new IllegalStateException(String.format(
                        "[%s] state 缺少 key=%s, 类型=%s", agentName, key, type.getSimpleName())));
    }

    /**
     * 通用类型安全读取（可选）
     */
    @SuppressWarnings("unchecked")
    protected <T> Optional<T> findTyped(OverAllState state, String key, Class<T> type) {
        return state.value(key)
                .map(v -> {
                    if (v == null) {
                        return null;
                    }
                    if (!type.isInstance(v)) {
                        throw new IllegalStateException(String.format(
                                "[%s] state key=%s 类型不匹配: 期望 %s, 实际 %s",
                                agentName, key, type.getSimpleName(), v.getClass().getSimpleName()));
                    }
                    return (T) v;
                });
    }
}
