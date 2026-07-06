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

    // ============ M10: LLM 调用超时控制 ============

    /** 单次 chatModel.call() 超时阈值(ms);超过则放弃该次调用走 mock 兜底 */
    protected static final long LLM_TIMEOUT_MS = 120_000;

    /** 单次 chatModel.call() 最大重试次数(超时或瞬时异常时);0 表示不重试 */
    protected static final int LLM_MAX_RETRIES = 2;

    /** 共享线程池用于把 chatModel.call() 包装成 Future,实现超时中断 */
    private static final java.util.concurrent.ExecutorService LLM_TIMEOUT_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "agent-llm-timeout");
                t.setDaemon(true);
                return t;
            });

    // ============ 工具调用轮次硬上限(配合 Prompt 优化)============
    //
    // 旧实现各 Agent 硬编码 maxRounds=15,LLM 经常跑满 15 轮才输出 JSON,
    // 既浪费 token 又触发 BaseAgent 强制收口。改用集中常量便于调优:
    // - Worker (Route/Itinerary/Budget):Prompt 已约束 4-6 轮,留 8 轮缓冲
    // - Validation:Prompt 已约束 ≤ 3 轮,留 4 轮缓冲
    // - Manager 首次 (askUser 澄清):1-2 轮足够,留 3 轮

    /** Worker (Route/Itinerary/Budget) 工具调用最大轮次。 */
    protected static final int LLM_MAX_WORKER_ROUNDS = 8;

    /** ValidationAgent 工具调用最大轮次(只需 calculator ≤ 1 次)。 */
    protected static final int LLM_MAX_VALIDATION_ROUNDS = 4;

    /** ManagerAgent 首次模式 (askUser 追问) 最大轮次。 */
    protected static final int LLM_MAX_MANAGER_FIRST_ROUNDS = 3;

    // ============ Graph 流事件发射器(C1 修复: 构造器注入替代 ThreadLocal) ============

    /**
     * 实例级 emitter 引用 — 由 GraphStreamRunner 在 build Graph 前通过 setEmitter() 注入.
     * <p>不再使用 ThreadLocal,避免并行节点(route + itinerary)线程切换时事件丢失.
     */
    private uno.zhuchen.workflow.util.GraphEventEmitter emitter;

    /**
     * 注入 Graph 事件发射器(由 TripPlanningGraphBuilder.build(emitter) 在装配时调用).
     * <p>对每个节点独立调用,确保即使 Graph 框架使用线程池调度,各 Agent 的 emitter
     * 引用都指向同一个 sink,从而并行节点的事件能正确汇聚.
     */
    public void setEmitter(uno.zhuchen.workflow.util.GraphEventEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * 发射 Graph 流事件 — 子类可通过此方法发送 node_data / node_progress 等事件
     */
    protected void emitEvent(StreamChunk event) {
        if (emitter != null) {
            emitter.emit(event);
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
     *
     * <p>traceId 链路追踪：从 state[trace_id] 读取 traceId,
     * 业务 log 显式拼 [traceId={}] 到消息中（不依赖 MDC/ThreadLocal）.
     *
     * <p>为何不用 Reactor Context: Graph 框架的 {@code NodeAction.apply}
     * 是阻塞同步调用, 不在 reactive 链上, ContextView 拿不到, 走 state 路径.
     */
    @Override
    public final Map<String, Object> apply(OverAllState state) throws Exception {
        long start = System.currentTimeMillis();
        String traceId = state.value(TripPlanningStateKeys.INPUT_TRACE_ID)
                .map(Object::toString).orElse("");
        String conversationId = state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID)
                .map(Object::toString).orElse("unknown");
        try {
            log.debug("[traceId={}] [{}] apply() 入口, conversationId={}, iterationCount={}",
                    traceId, agentName, conversationId,
                    state.value(TripPlanningStateKeys.CONTROL_ITERATION_COUNT).orElse(0));

            Map<String, Object> result = doExecute(state);
            long duration = System.currentTimeMillis() - start;
            log.debug("[traceId={}] [{}] doExecute 完成, 耗时 {}ms, 输出 keys={}",
                    traceId, agentName, duration, result.keySet());
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[traceId={}] [{}] doExecute 失败, 耗时 {}ms, error={}",
                    traceId, agentName, duration, e.getMessage(), e);
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
     * <p>M10 修复：包装 {@link #callWithTimeout} 实现超时控制 + 重试，
     * 防止 LLM API 慢响应阻塞 SSE 流。
     *
     * @param traceId      链路追踪 ID
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 文本回复
     */
    protected String callLLM(String traceId, String systemPrompt, String userPrompt) {
        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        );
        // 显式传空 vararg,便于 mockito 严格模式 stub
        AssistantMessage resp = callWithTimeout(traceId, messages, new org.springframework.ai.tool.ToolCallback[0]);
        return resp.getText();
    }

    /**
     * 把同步 chatModel.call 包装成有超时 + 重试的调用.
     * <p>由于 ChatModel.call 是阻塞同步接口,无法真正中断正在执行的 HTTP 请求,
     * 这里使用 Future.get(timeout) 模式:超时就放弃本次结果但线程仍会跑完.
     * 实际生产建议在 Spring AI 层改用 ReactiveClient 才能彻底中断.
     *
     * @param traceId  链路追踪 ID, 用于超时/重试日志
     * @return LLM 响应;超时时抛 TimeoutException 让调用方走兜底
     */
    private AssistantMessage callWithTimeout(String traceId, List<Message> messages,
                                             org.springframework.ai.tool.ToolCallback[] tools) {
        java.util.concurrent.TimeoutException lastTimeout = null;
        for (int attempt = 0; attempt <= LLM_MAX_RETRIES; attempt++) {
            java.util.concurrent.Future<AssistantMessage> future =
                    LLM_TIMEOUT_POOL.submit(() -> chatModel.call(messages, tools));
            try {
                return future.get(LLM_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                lastTimeout = te;
                future.cancel(true);
                log.warn("[traceId={}] [{}] LLM 调用超时 ({}ms), attempt={}/{}",
                        traceId, agentName, LLM_TIMEOUT_MS, attempt + 1, LLM_MAX_RETRIES + 1);
            } catch (java.util.concurrent.ExecutionException ee) {
                // 拆包原始异常,如果是瞬时可重试错误(IOException/ConnectException)继续重试,否则抛出
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                if (attempt < LLM_MAX_RETRIES && isTransientError(cause)) {
                    log.warn("[traceId={}] [{}] LLM 调用瞬时异常 ({}), 重试 attempt={}",
                            traceId, agentName, cause.getMessage(), attempt + 1);
                    continue;
                }
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM 调用被中断", ie);
            }
        }
        throw new RuntimeException("LLM 调用超时 " + LLM_TIMEOUT_MS + "ms (重试 " + LLM_MAX_RETRIES + " 次)",
                lastTimeout);
    }

    private static boolean isTransientError(Throwable t) {
        String name = t.getClass().getName();
        return name.contains("IOException")
                || name.contains("ConnectException")
                || name.contains("SocketTimeoutException")
                || name.contains("ResourceAccessException");
    }

    /**
     * LLM 带工具循环调用 — LLM 可自主调用 askUser 等工具,Java 只负责执行并回传结果。
     *
     * <p>LLM 自主决定何时提问、何时输出最终答案,使需求澄清过程完全由 LLM 驱动。
     *
     * @param traceId      链路追踪 ID
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户消息
     * @param tools        可用工具
     * @param maxRounds    最大工具调用轮次
     * @return LLM 最终输出的文本
     */
    protected String callLLMWithTools(String traceId, String systemPrompt, String userPrompt,
                                       org.springframework.ai.tool.ToolCallback[] tools, int maxRounds) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        String convId = extractConversationId(userPrompt);

        emitEvent(StreamChunk.nodeProgress(agentName, "thinking", "开始分析...", convId));

        for (int round = 0; round < maxRounds; round++) {
            // M10 修复: 走 callWithTimeout 包装,避免 LLM 卡死阻塞 SSE
            AssistantMessage response = callWithTimeout(traceId, messages, tools);
            messages.add(response);

            if (!response.hasToolCalls()) {
                String text = response.getText();
                log.debug("[traceId={}] [{}] LLM 输出最终结果, text长度={}",
                        traceId, agentName, text != null ? text.length() : 0);
                emitEvent(StreamChunk.nodeProgress(agentName, "thinking",
                        "分析完成", convId));
                return text != null ? text : "";
            }

            log.debug("[traceId={}] [{}] LLM 请求调用 {} 个工具: {}",
                    traceId, agentName,
                    response.getToolCalls().size(),
                    response.getToolCalls().stream()
                            .map(tc -> tc.name() + "(" + truncate(tc.arguments(), 80) + ")")
                            .toList());

            for (AssistantMessage.ToolCall tc : response.getToolCalls()) {
                org.springframework.ai.tool.ToolCallback tool = findTool(tools, tc.name());
                if (tool == null) {
                    log.warn("[traceId={}] [{}] 未知工具: {}, 跳过", traceId, agentName, tc.name());
                    continue;
                }

                // 发射工具调用事件 (Graph 流)
                emitEvent(StreamChunk.nodeProgress(agentName, "tool_call",
                        tc.name() + "(" + truncate(tc.arguments(), 60) + ")", convId));

                try {
                    if ("askUser".equals(tc.name())) {
                        AskUserTool.setContext(convId, traceId);
                    }
                    long t0 = System.currentTimeMillis();
                    String result = tool.call(tc.arguments());
                    long elapsed = System.currentTimeMillis() - t0;
                    log.debug("[traceId={}] [{}] 工具 {} 返回 ({}ms): {}",
                            traceId, agentName, tc.name(), elapsed, truncate(result, 120));

                    emitEvent(StreamChunk.nodeProgress(agentName, "tool_result",
                            tc.name() + " 返回 (" + elapsed + "ms)", convId));

                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    tc.id(), tc.name(), result)))
                            .build());
                } catch (Exception e) {
                    log.warn("[traceId={}] [{}] 工具 {} 异常 ({}): {}",
                            traceId, agentName, tc.name(),
                            e.getClass().getSimpleName(), e.getMessage());
                    emitEvent(StreamChunk.nodeProgress(agentName, "tool_result",
                            tc.name() + " 异常: " + e.getMessage(), convId));
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    tc.id(), tc.name(), "失败: " + e.getMessage())))
                            .build());
                } finally {
                    AskUserTool.clearContext();
                }
            }

            emitEvent(StreamChunk.nodeProgress(agentName, "thinking",
                    "分析工具返回结果...", convId));
        }
        log.warn("[traceId={}] [{}] 达到最大工具调用轮次 {}, 返回空",
                traceId, agentName, maxRounds);
        emitEvent(StreamChunk.nodeProgress(agentName, "thinking",
                "达到最大轮次，强制结束", convId));
        return "";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ============ 纯 JSON 协议检查(Prompt 优化配套)============

    /**
     * 校验 LLM 输出是否严格符合"纯 JSON"协议。
     * <p>判定条件:
     * <ul>
     *   <li>非 null / 非空</li>
     *   <li>trim 后不含 markdown 代码块标记(```)</li>
     *   <li>trim 后首字符是 { 或 [</li>
     *   <li>trim 后末字符是 } 或 ]</li>
     * </ul>
     *
     * @return true=合规, false=含 markdown / 前后文字 / 空内容
     */
    protected boolean isPureJson(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        if (trimmed.contains("```")) return false;
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') return false;
        char last = trimmed.charAt(trimmed.length() - 1);
        return last == '}' || last == ']';
    }

    /**
     * 当 LLM 输出不严格符合 JSON 协议时,记 WARN 日志(便于事后追溯)。
     * <p>不抛异常,因为下游 JsonExtractor 还有 5 层兜底策略能抢救;
     * 这里仅记录便于诊断哪些 Agent / 哪些场景下 LLM 经常违规。
     */
    protected void warnIfNotPureJson(String text) {
        if (isPureJson(text)) return;
        log.warn("[{}] LLM 输出不严格符合 JSON 协议(可能含 markdown/前后文字),内容前 200 字符: {}",
                agentName, truncate(text, 200));
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

    /** 读取 List<DayPlan>（可选），元素类型做运行时校验（M7 修复） */
    protected Optional<List<DayPlan>> findItinerary(OverAllState state) {
        return findTypedList(state, TripPlanningStateKeys.WORKER_ITINERARY, DayPlan.class);
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

    /**
     * 通用类型安全读取 List<T>（M7 修复配套）.
     * <p>对 List 元素也做 elementType.isInstance 校验,防止 Graph 框架
     * 反序列化时元素类型丢失（如变成 {@code List<LinkedHashMap>}）导致下游 NPE.
     *
     * @param state       Graph 状态
     * @param key         状态键
     * @param elementType 列表元素类型
     */
    @SuppressWarnings("unchecked")
    protected <T> Optional<List<T>> findTypedList(OverAllState state, String key, Class<T> elementType) {
        return state.value(key).map(v -> {
            if (v == null) return null;
            if (!(v instanceof List<?> list)) {
                throw new IllegalStateException(String.format(
                        "[%s] state key=%s 期望 List 类型, 实际 %s",
                        agentName, key, v.getClass().getSimpleName()));
            }
            for (int i = 0; i < list.size(); i++) {
                Object elem = list.get(i);
                if (elem != null && !elementType.isInstance(elem)) {
                    throw new IllegalStateException(String.format(
                            "[%s] state key=%s 第 %d 个元素类型不匹配: 期望 %s, 实际 %s",
                            agentName, key, i, elementType.getSimpleName(),
                            elem.getClass().getSimpleName()));
                }
            }
            return (List<T>) list;
        });
    }

    /**
     * 读取 Manager 注入的 retryHint（C6 修复配套）。
     * <p>ManagerAgent.handleFallback 把 ValidationAgent 的 failures 转写成 retryHint，
     * 写入 {@link TripPlanningStateKeys#CONTROL_WARNINGS}。本方法提取文本片段供 Worker
     * 拼入 context，让回退重试时 LLM 能看到上次失败原因。
     *
     * @param state Graph 状态
     * @return 多行 hint 字符串；无则返回空串
     */
    protected String formatRetryHint(OverAllState state) {
        Optional<Object> warnings = state.value(TripPlanningStateKeys.CONTROL_WARNINGS);
        if (warnings.isEmpty()) return "";
        Object raw = warnings.get();
        if (raw == null) return "";
        // CONTROL_WARNINGS 用 APPEND strategy,值可能是 List<String> 也可能是 String
        if (raw instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) != null) {
                    sb.append("- ").append(list.get(i));
                    if (i < list.size() - 1) sb.append("\n");
                }
            }
            return sb.toString();
        }
        return raw.toString();
    }
}
