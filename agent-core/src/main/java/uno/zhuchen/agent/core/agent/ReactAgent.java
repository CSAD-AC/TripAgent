package uno.zhuchen.agent.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.config.AgentConfig;
import uno.zhuchen.agent.common.TraceContext;
import uno.zhuchen.agent.domain.dto.ChatDTO;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.core.llm.ChatModel;
import uno.zhuchen.agent.core.memory.ChatMemory;
import uno.zhuchen.agent.core.tool.AskUserTool;
import uno.zhuchen.agent.core.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReAct 循环核心引擎
 *
 * 实现 思考(Reason) → 行动(Act) → 观察(Observe) 迭代范式。
 * 当前阶段（无工具）：行动 = 输出最终答案，每次调用 LLM 即为一轮推理。
 * 后续扩展：当 LLM 返回 toolCalls 时，执行工具并将结果注入下一轮迭代。
 *
 *
 * 流程（Reason → Act → Observe 迭代范式）：
 *   1. LLM 接收 SystemPrompt + 历史消息 → 输出推理
 *   2. 判断是否有 toolCalls
 *      - 有：执行工具 → 观察结果 → 加入消息历史 → 回到第 1 步
 *      - 无：返回最终答案
 *
 * <p>traceId 链路追踪:
 * <ul>
 *   <li>traceId 由 Controller 入口 contextWrite 注入 Reactor Context</li>
 *   <li>{@link #stream} 不再接收 traceId 参数, 业务 log 通过
 *       {@link TraceContext#currentTraceId(reactor.util.context.ContextView)}
 *       从 {@code sink.currentContext()} 读取</li>
 *   <li>递归调用 {@link #nextIteration} 时无需透传 traceId, Reactor Context 自动跨订阅边界</li>
 *   <li>调用 {@link AskUserTool#setContext} 前用
 *       {@link TraceContext#currentTraceIdMono()}
 *       阻塞取值后再传</li>
 * </ul>
 */
public class ReactAgent {

    private static final Logger log = LoggerFactory.getLogger(ReactAgent.class);

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final AgentConfig config;
    private final ToolRegistry toolRegistry;

    /** 每会话并发控制，拒绝同一 conversationId 的并行请求 */
    private final ConcurrentHashMap<String, AtomicBoolean> conversationLocks = new ConcurrentHashMap<>();

    public ReactAgent(ChatModel chatModel, ChatMemory chatMemory, AgentConfig config, ToolRegistry toolRegistry) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.config = config;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 同步调用：执行 ReAct 循环，返回完整结果
     */
    public ChatDTO call(String userInput, String conversationId) {
        long start = System.currentTimeMillis();
        List<String> reasoningSteps = new ArrayList<>();

        try {
            // 1. 初始化状态
            AgentState state = new AgentState(conversationId, config.getSystemPrompt(), userInput);
            log.debug("Agent[{}] 开始 ReAct 循环, maxIterations={}",
                    state.getConversationId(), config.getMaxIterations());

            // 载入Tool
            ToolCallback[] tools = toolRegistry.getAll();
            log.debug("Agent[{}] 可用工具数: {}, 工具列表: {}",
                    state.getConversationId(),
                    tools.length,
                    java.util.Arrays.stream(tools)
                            .map(t -> t.getToolDefinition().name())
                            .collect(java.util.stream.Collectors.toList()));

            // 2. 加载历史记忆（续聊）
            List<Message> history = chatMemory.load(state.getConversationId());
            if (!history.isEmpty()) {
                log.debug("Agent[{}] 加载 {} 条历史消息", state.getConversationId(), history.size());
                // 将历史消息插入到 system prompt 之后、当前用户消息之前
                history.forEach(msg -> state.getMessages().add(state.getMessages().size() - 1, msg));
            }

            // 3. ReAct 循环
            AssistantMessage finalResponse = null;

            for (int i = 0; i < config.getMaxIterations(); i++) {
                log.debug("Agent[{}] 第 {}/{} 轮迭代", state.getConversationId(),
                        i + 1, config.getMaxIterations());

                // --- Reason: 调用 LLM ---
                AssistantMessage response = chatModel.call(state.getFullMessages(), tools);
                String content = response.getText();

                // 记录推理步骤
                reasoningSteps.add("Step " + (i + 1) + ": " + truncate(content, 200));

                // --- Decide: 检查是否有工具调用 ---
                if (response.hasToolCalls()) {
                    log.debug("Agent[{}] 检测到 {} 个工具调用请求",
                            state.getConversationId(), response.getToolCalls().size());

                    // 将 LLM 的 toolCalls 请求加入消息历史
                    state.addReasoningResult(response);

                    // --- Act: 执行工具，收集观察结果 ---
                    List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

                    for (AssistantMessage.ToolCall toolCall : response.getToolCalls()) {
                        // 在工具注册表中查找对应的工具
                        ToolCallback tool = toolRegistry.getByName(toolCall.name());

                        if (tool == null) {
                            // 未知工具 → 返回错误信息让 LLM 自行处理
                            log.warn("Agent[{}] 未知工具: {}", state.getConversationId(), toolCall.name());
                            toolResponses.add(new ToolResponseMessage.ToolResponse(
                                    toolCall.id(), toolCall.name(),
                                    String.format("错误: 未知工具 '%s'，没有找到对应的实现", toolCall.name())));
                            continue;
                        }

                        log.debug("Agent[{}] 执行工具: {} (id={})",
                                state.getConversationId(), toolCall.name(), toolCall.id());

                        // 反问工具需要 conversationId,放在 ThreadLocal 里传
                        // 用 try-finally 保证清理,防止线程复用导致串号
                        // FIXME 同步 call() 入口暂未接入链路追踪, 后续重构 stream() 时统一处理
                        // setContext 仅对 askUser 调用, 与 stream() 路径 executeOneTool 保持一致
                        boolean needsAskUserContext = "askUser".equals(toolCall.name());
                        try {
                            if (needsAskUserContext) {
                                // 同步入口无 traceId, 传空字符串(同步接口不参与链路追踪)
                                AskUserTool.setContext(state.getConversationId(), "");
                            }
                            // Observe: 执行工具 → 获取结果
                            String result = tool.call(toolCall.arguments());
                            log.debug("Agent[{}] 工具 {} 执行完成, 结果长度={}",
                                    state.getConversationId(), toolCall.name(), result.length());
                            toolResponses.add(new ToolResponseMessage.ToolResponse(
                                    toolCall.id(), toolCall.name(), result));
                        } catch (Exception e) {
                            log.error("Agent[{}] 工具 {} 执行异常",
                                    state.getConversationId(), toolCall.name(), e);
                            toolResponses.add(new ToolResponseMessage.ToolResponse(
                                    toolCall.id(), toolCall.name(),
                                    "工具执行失败: " + e.getMessage()));
                        } finally {
                            if (needsAskUserContext) {
                                AskUserTool.clearContext();
                            }
                        }
                    }

                    // 将工具响应注入消息历史 → 回到 Reason 步骤开始下一轮迭代
                    // 每个 ToolResponse 单独作为一条消息（避免 DeepSeek 多响应合并问题）
                    for (ToolResponseMessage.ToolResponse tr : toolResponses) {
                        state.getMessages().add(ToolResponseMessage.builder()
                                .responses(List.of(tr))
                                .build());
                    }
                    continue;
                }

                // --- Act: 无工具调用 → 这就是最终答案 ---
                finalResponse = response;
                state.addReasoningResult(response);
                log.debug("Agent[{}] 第 {} 轮得到最终答案", state.getConversationId(), i + 1);
                break;
            }

            long duration = System.currentTimeMillis() - start;

            if (finalResponse != null) {
                // 保存对话历史
                chatMemory.save(state.getConversationId(), state.getMessages());
                return ChatDTO.success(
                        state.getConversationId(), userInput,
                        finalResponse.getText(),
                        state.getIterationCount(),
                        reasoningSteps,
                        duration
                );
            }

            // 达到最大迭代次数仍未得出最终答案
            chatMemory.save(state.getConversationId(), state.getMessages());
            String lastContent = !state.getMessages().isEmpty()
                    ? state.getMessages().get(state.getMessages().size() - 1).getText()
                    : "";
            return ChatDTO.maxIterations(
                    state.getConversationId(), userInput,
                    lastContent,
                    state.getIterationCount(),
                    duration
            );

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Agent 执行异常", e);
            return ChatDTO.error(conversationId, userInput, e.getMessage(), duration);
        }
    }

    /**
     * 全流程流式调用：LLM 思考 token、工具调用、最终答案全程流式推送
     *
     * 流式 SSE 事件序列示例：
     *   thinking_token → thinking_token → ... → tool_call_start → tool_call → tool_result
     *   → thinking_token → ... → iteration_separator
     *   → thinking_token → ... → final
     *
     * 核心改进：使用 stream() 替代阻塞的 call()，
     * 每个 token 在 flatMap 中实时推送 thinking_token 事件，
     * 工具调用阶段则推送 tool_call / tool_result / tool_error 事件。
     *
     * 采用纯响应式递归实现，避免 blockLast 导致的跨线程 sink.next() 问题，
     * 确保 SSE 事件逐条即时 flush 到前端。
     *
     * <p>traceId 由 Controller contextWrite 注入 Reactor Context, 本方法不接 traceId 参数,
     * 所有业务 log 通过 {@link reactor.core.publisher.Flux#handle(java.util.function.BiConsumer)}
     * 在 {@code sink.currentContext()} 上读取.
     */
    public Flux<StreamChunk> stream(String userInput, String conversationId) {
        long start = System.currentTimeMillis();

        // 防止同一 conversationId 被并发请求处理（导致消息历史交叉污染）
        AtomicBoolean inProgress =
                conversationLocks.computeIfAbsent(conversationId,
                        k -> new AtomicBoolean(false));
        if (!inProgress.compareAndSet(false, true)) {
            // 并发拒绝 log 需要 traceId, 在 reactive 链上用 deferContextual 读
            // traceId 由 Controller 入口 contextWrite 注入, 经 Flux.merge 透传到本链, 不必再写
            return TraceContext.currentTraceIdMono()
                    .doOnNext(t -> log.warn("[traceId={}] 会话[{}] 已在处理中，拒绝并发请求",
                            t, conversationId))
                    .thenMany(Flux.just(StreamChunk.error(conversationId,
                            "当前会话正在处理中，请等待完成后再发新消息",
                            System.currentTimeMillis() - start)));
        }

        AgentState state = new AgentState(conversationId, config.getSystemPrompt(), userInput);

        // 加载历史记忆（非阻塞操作）
        List<Message> history = chatMemory.load(state.getConversationId());
        if (!history.isEmpty()) {
            history.forEach(msg -> state.getMessages().add(state.getMessages().size() - 1, msg));
        }

        ToolCallback[] allTools = toolRegistry.getAll();

        return nextIteration(state, allTools, 0, start)
                .doFinally(signalType -> {
                    inProgress.set(false);
                    conversationLocks.remove(conversationId);
                });
    }

    /**
     * 单次工具执行的不可变结果.
     *
     * <p>用 {@link Outcome} 枚举显式区分 4 种结局, 渲染层据此选择 SSE 事件类型,
     * 避免依赖字符串包含判断.
     */
    private record ToolExecutionResult(AssistantMessage.ToolCall call,
                                         String response,
                                         long durationMs,
                                         Outcome outcome) {

        enum Outcome {
            /** 工具正常返回 */
            SUCCESS,
            /** 工具未注册 */
            UNKNOWN_TOOL,
            /** 与之前调用 name + args 完全相同, 已跳过执行 */
            DUPLICATE,
            /** 执行抛出异常 */
            ERROR
        }
    }

    /**
     * 一轮 ReAct 推理的可变累积器 (阶段 1 局部状态).
     *
     * <p>封装 StringBuilder + LinkedHashMap, 把"累积文本 + 按 id 去重 tool calls"
     * 的逻辑收口到一处, 避免 nextIteration 流式回调里出现散落的 thoughtBuilder.append
     * 与 toolCallMap.put.
     *
     * <p>可变 + 边界读取: 阶段 1 每 chunk O(1) 直接追加, 阶段边界一次性以
     * {@link #thought()} + {@link #toolCalls()} 读取不可变视图, 避免每 chunk
     * 不可变复制与 O(N²) 字符串拼接.
     */
    private static final class RoundAccumulator {
        private final StringBuilder thoughtBuilder = new StringBuilder();
        private final Map<String, AssistantMessage.ToolCall> toolCallMap = new LinkedHashMap<>();

        /** 追加一个 chunk 的文本与 tool calls (按 id 后写覆盖, 同一调用跨 chunk 时保留最完整 arguments) */
        void append(AssistantMessage chunk) {
            String text = chunk.getText();
            if (text != null && !text.isEmpty()) {
                thoughtBuilder.append(text);
            }
            if (chunk.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : chunk.getToolCalls()) {
                    toolCallMap.put(tc.id(), tc);
                }
            }
        }

        /** 边界读取: 完整推理文本 */
        String thought() {
            return thoughtBuilder.toString();
        }

        /** 边界读取: 工具调用不可变快照 */
        List<AssistantMessage.ToolCall> toolCalls() {
            return List.copyOf(toolCallMap.values());
        }
    }

    /**
     * 递归执行一轮 ReAct 迭代.
     *
     * <p>两阶段装配:
     * <ul>
     *   <li>阶段 1 (thinkingStream): LLM 流式输出, 实时推送 thinking_token, 同时累积 RoundResult</li>
     *   <li>阶段 2 (continuationStream): thinkingStream 完成后, 在 boundedElastic 上
     *       根据累积结果决定下一步 (最终答案 / 工具执行 / 递归下一轮)</li>
     * </ul>
     *
     * <p>关键不变量:
     * <ul>
     *   <li>thinking_token 实时推送 (Flux.handle 逐 chunk 处理, 不用 Mono.collect 全缓冲)</li>
     *   <li>blocking I/O (tool.call) 隔离在 boundedElastic (Mono.fromSupplier + subscribeOn)</li>
     *   <li>递归订阅安全 (Mono.fromSupplier 一次性执行, 避免 Flux.defer 多订阅陷阱)</li>
     * </ul>
     */
    private Flux<StreamChunk> nextIteration(AgentState state, ToolCallback[] tools,
                                             int iteration, long start) {
        if (iteration >= config.getMaxIterations()) {
            return Flux.just(StreamChunk.error(state.getConversationId(),
                    "未能在最大迭代次数内得出最终答案", System.currentTimeMillis() - start));
        }

        // 用 Flux.deferContextual 非阻塞读取 traceId, 避免在 reactor event loop 线程上 .block()
        // ContextView 由上游订阅时传入 (reactor Context 自动跨订阅边界传播)
        return Flux.deferContextual(ctx -> {
            String traceId = TraceContext.currentTraceId(ctx);
            int iterNum = iteration + 1;
            // ===== 阶段 1 累积: RoundAccumulator 封装 StringBuilder + LinkedHashMap, per-chunk O(1) =====
            RoundAccumulator acc = new RoundAccumulator();

            Flux<StreamChunk> thinkingStream = chatModel.stream(state.getFullMessages(), tools)
                    .handle((resp, sink) -> {
                        AssistantMessage msg = resp.getResult().getOutput();
                        acc.append(msg);
                        String text = msg.getText();
                        if (text != null && !text.isEmpty()) {
                            sink.next(StreamChunk.thinkingToken(text,
                                    state.getConversationId(), "迭代" + iterNum));
                        }
                    });

            // ===== 阶段 2: 边界一次性物化 + 批量处理 (boundedElastic) =====
            // Mono.fromSupplier 保证 supplier 仅执行一次, 避免 Flux.defer + 递归订阅的副作用重放
            Flux<StreamChunk> continuationStream = Mono.fromSupplier(() ->
                            proceedAfterReasoning(state, tools, acc.thought(), acc.toolCalls(),
                                    traceId, iterNum, iteration, start))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(flux -> flux);

            return Flux.concat(thinkingStream, continuationStream)
                    .onErrorResume(e -> saveAndEmitError(state, traceId, e, start));
        });
    }

    /**
     * 阶段 2 路由: 根据累积结果决定最终答案或进入工具执行分支.
     */
    private Flux<StreamChunk> proceedAfterReasoning(AgentState state, ToolCallback[] tools,
                                                       String thought, List<AssistantMessage.ToolCall> toolCalls,
                                                       String traceId, int iterNum,
                                                       int iteration, long start) {
        if (toolCalls.isEmpty()) {
            // 无工具调用 → 最终答案
            AssistantMessage response = AssistantMessage.builder()
                    .content(thought)
                    .build();
            state.addReasoningResult(response);
            chatMemory.save(state.getConversationId(), state.getMessages());
            log.debug("[traceId={}] 第 {} 轮无工具调用, 得到最终答案", traceId, iterNum);
            return Flux.just(StreamChunk.final_(state.getConversationId(), thought,
                    System.currentTimeMillis() - start));
        }

        // 有工具调用 → 注入 ASSISTANT(tool_calls) 后转入工具执行
        state.addReasoningResult(AssistantMessage.builder()
                .content(thought)
                .toolCalls(toolCalls)
                .build());

        return executeToolsAndContinue(state, tools, toolCalls,
                traceId, iterNum, iteration, start);
    }

    /**
     * 工具执行 + TOOL 响应注入历史 + SSE 事件渲染 + 递归下一轮 (并行 + 完成即发版).
     *
     * <p>三阶段装配:
     * <ul>
     *   <li><b>Phase A (toolCallEvents)</b>: 立即按 LLM 调用顺序发射 toolCallStart + 全部 tool_call,
     *       前端 T=0 时即可看到 N 个工具进入 running 状态</li>
     *   <li><b>Phase B (toolResultEvents)</b>: 并行执行 (flatMap + maxConcurrency=4),
     *       每个工具完成即发射对应 tool_result / tool_error (按完成顺序).
     *       使用 ConcurrentHashMap.newKeySet() 跨线程原子去重,
     *       doOnNext 把结果按 call.id() 累积到 resultById 供 Phase C 查询</li>
     *   <li><b>Phase C (historyAndSeparator)</b>: 等 Phase B 全部完成后, 按 LLM 原序遍历 toolCalls,
     *       从 resultById 查 ToolExecutionResult 注入 state.messages (单线程, ArrayList 安全),
     *       再发射 iterationSeparator</li>
     * </ul>
     *
     * <p>前端契约: tool_call / tool_result / tool_error 都携带 toolCallId,
     * 前端用 {@code findIndex(c =&gt; c.toolCallId === event.toolCallId)} 匹配具体调用,
     * 不依赖事件到达顺序.
     *
     * <p>线程模型: Phase A 与 Phase B 在 boundedElastic 上 (与上游 subscribeOn 兼容),
     * Phase C 单次执行 (operator chain 屏障保证 resultById 已完整).
     */
    private Flux<StreamChunk> executeToolsAndContinue(AgentState state, ToolCallback[] tools,
                                                      List<AssistantMessage.ToolCall> toolCalls,
                                                        String traceId, int iterNum,
                                                        int iteration, long start) {
        // ===== Phase A: 立即发射 toolCallStart + 全部 tool_call (按 LLM 原序) =====
        Flux<StreamChunk> toolCallEvents = Flux.concat(
                Flux.just(StreamChunk.toolCallStart(state.getConversationId(), "开始执行工具调用")),
                Flux.fromIterable(toolCalls)
                        .map(call -> StreamChunk.toolCall(
                                call.id(), call.name(), call.arguments(),
                                state.getConversationId())));

        // ===== Phase B: 并行执行 + 完成即发 tool_result/tool_error =====
        Map<String, ToolExecutionResult> resultById = new ConcurrentHashMap<>();
        Set<String> seen = ConcurrentHashMap.newKeySet();

        Flux<StreamChunk> toolResultEvents = Flux.fromIterable(toolCalls)
                .flatMap(call -> Mono.fromCallable(() -> {
                            String key = call.name() + "::" + call.arguments();
                            if (!seen.add(key)) {
                                return new ToolExecutionResult(call,
                                        "重复调用已跳过,使用第一次调用的结果", 0L,
                                        ToolExecutionResult.Outcome.DUPLICATE);
                            }
                            return executeOneTool(state, call, traceId);
                        })
                        .subscribeOn(Schedulers.boundedElastic()), 4)
                .doOnNext(r -> resultById.put(r.call().id(), r))
                .map(r -> renderSingleResultEvent(state, r));

        // ===== Phase C: 历史注入 (按 LLM 原序) + iterationSeparator (单线程) =====
        Flux<StreamChunk> historyAndSeparator = Flux.defer(() -> {
                    for (AssistantMessage.ToolCall call : toolCalls) {
                        ToolExecutionResult r = resultById.get(call.id());
                        state.getMessages().add(ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                        call.id(), call.name(), r.response())))
                                .build());
                    }
                    return Flux.just(StreamChunk.iterationSeparator(
                            state.getConversationId(), "第" + iterNum + "轮完成"));
                })
                .subscribeOn(Schedulers.boundedElastic());

        // ===== 拼接: A → B → C → 递归下一轮 =====
        return toolCallEvents
                .concatWith(toolResultEvents)
                .concatWith(historyAndSeparator)
                .concatWith(nextIteration(state, tools, iteration + 1, start));
    }

    /**
     * 单工具执行 (同步阻塞, 由上游 subscribeOn 调度到 boundedElastic).
     *
     * <p>显式枚举结局: UNKNOWN_TOOL / SUCCESS / ERROR, 渲染层据此选择 toolResult 或 toolError.
     * 复用 AskUserTool 的 ThreadLocal 上下文 (sync 入口必需).
     *
     * <p>setContext 仅对 askUser 调用: 只有 askUser 内部读 ThreadLocal,
     * 且 setContext 内置的 ACTIVE_CONVERSATIONS 互斥检查本意是防止同 conversationId
     * 两个 askUser 阻塞覆盖 ThreadLocal, 不应误伤其他工具.
     * 之前无差别 setContext 会导致 askUser + 其他工具并行时, 其他工具被 AskUserBusyException
     * 错误标记失败, 污染消息历史触发 DeepSeek 400 (insufficient tool messages).
     */
    private ToolExecutionResult executeOneTool(AgentState state,
                                                 AssistantMessage.ToolCall call,
                                                 String traceId) {
        ToolCallback tool = toolRegistry.getByName(call.name());
        if (tool == null) {
            String errorMsg = "未知工具: " + call.name();
            log.warn("[traceId={}] [{}] 未知工具: {}",
                    traceId, state.getConversationId(), call.name());
            return new ToolExecutionResult(call, errorMsg, 0L,
                    ToolExecutionResult.Outcome.UNKNOWN_TOOL);
        }

        long t0 = System.currentTimeMillis();
        boolean needsAskUserContext = "askUser".equals(call.name());
        try {
            // 反问工具需要 conversationId + traceId 上下文 (sync 入口, ThreadLocal 透传)
            if (needsAskUserContext) {
                AskUserTool.setContext(state.getConversationId(),
                        traceId == null ? "" : traceId);
            }

            String result = tool.call(call.arguments());
            long duration = System.currentTimeMillis() - t0;
            log.debug("[traceId={}] [{}] 工具 {} 执行完成, 耗时={}ms, 结果长度={}",
                    traceId, state.getConversationId(), call.name(), duration, result.length());
            return new ToolExecutionResult(call, result, duration,
                    ToolExecutionResult.Outcome.SUCCESS);
        } catch (Exception e) {
            String errorMsg = "工具执行失败: " + e.getMessage();
            log.warn("[traceId={}] [{}] 工具 {} 执行异常: {}",
                    traceId, state.getConversationId(), call.name(), errorMsg);
            return new ToolExecutionResult(call, errorMsg,
                    System.currentTimeMillis() - t0,
                    ToolExecutionResult.Outcome.ERROR);
        } finally {
            if (needsAskUserContext) {
                AskUserTool.clearContext();
            }
        }
    }

    /**
     * 把单个 ToolExecutionResult 渲染为一条 SSE 完成事件 (tool_result 或 tool_error).
     *
     * <p>tool_call 已在 {@link #executeToolsAndContinue} Phase A 按 LLM 原序发射,
     * 此处只发射完成事件. 前端按 event.toolCallId 匹配对应的 running 工具,
     * 不依赖事件到达顺序.
     *
     * <p>事件分支:
     * <ul>
     *   <li>DUPLICATE: toolResult("重复调用已跳过")</li>
     *   <li>UNKNOWN_TOOL: toolError(错误消息)</li>
     *   <li>SUCCESS: toolResult(结果摘要)</li>
     *   <li>ERROR: toolError(异常消息)</li>
     * </ul>
     */
    private StreamChunk renderSingleResultEvent(AgentState state, ToolExecutionResult r) {
        AssistantMessage.ToolCall call = r.call();
        return switch (r.outcome()) {
            case DUPLICATE -> StreamChunk.toolResult(call.id(), call.name(),
                    "重复调用已跳过", state.getConversationId());
            case UNKNOWN_TOOL -> StreamChunk.toolError(call.id(), call.name(),
                    r.response(), state.getConversationId());
            case SUCCESS -> StreamChunk.toolResult(call.id(), call.name(),
                    truncate(r.response(), 300), state.getConversationId());
            case ERROR -> StreamChunk.toolError(call.id(), call.name(),
                    r.response(), state.getConversationId());
        };
    }

    /**
     * 异常分支收口: 保存历史 + 推送 error 事件.
     *
     * <p>原代码散落在正常路径与 onErrorResume 两处的 chatMemory.save 合并到此处,
     * 消除状态更新分支重复.
     */
    private Flux<StreamChunk> saveAndEmitError(AgentState state, String traceId,
                                                  Throwable e, long start) {
        log.error("[traceId={}] [{}] ReAct 流式处理异常",
                traceId, state.getConversationId(), e);
        chatMemory.save(state.getConversationId(), state.getMessages());
        return Flux.just(StreamChunk.error(state.getConversationId(),
                "处理异常: " + e.getMessage(), System.currentTimeMillis() - start));
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}