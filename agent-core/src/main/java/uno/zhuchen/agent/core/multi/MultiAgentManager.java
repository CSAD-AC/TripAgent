package uno.zhuchen.agent.core.multi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.config.AgentConfig;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.agent.AgentState;
import uno.zhuchen.agent.core.llm.ChatModel;
import uno.zhuchen.agent.core.memory.ChatMemory;
import uno.zhuchen.agent.core.tool.AskUserTool;
import uno.zhuchen.agent.core.tool.AskUserToolCallback;
import uno.zhuchen.agent.core.tool.CalculatorTool;
import uno.zhuchen.agent.core.tool.ToolRegistry;
import uno.zhuchen.agent.domain.dto.StreamChunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multi-Agent 主管调度器 — ManagerAgent 的 ReAct 循环载体
 *
 * <p>职责: 主管(Supervisor)按 ReAct 范式拆解任务 → 调度子代理(Tool) →
 * 汇聚结果 → 输出最终回答, 全程 SSE 流式推送.
 *
 * <p>事件序列(与 ReAct 路径同构, 前端复用现有渲染):
 * <pre>
 *   (controller 已发 session_init)
 *   → thinking_token(主管思考)
 *   → tool_call_start → tool_call → tool_result / tool_error  (子代理调度)
 *   → iteration_separator
 *   → (thinking_token → ... → final)ⁿ
 * </pre>
 * 子代理内部完整生命周期(thinking_token / tool_call / tool_result / final)经
 * node_progress(节点 = 子代理名)实时上抛, 供前端 MultiAgentProgress 子代理工作区展示.
 *
 * <p>与 ReactAgent 的区别: 工具集是"子代理 + 反问 + 计算器", 而非 MCP 全量工具;
 * 系统提示词是主管调度提示词; 方案 C 起同轮多个子代理并行调度(boundedElastic + flatMap).
 *
 * <p>traceId / modelId 由 Controller 解析后以参数传入(循环跑在 boundedElastic
 * 的 Mono.fromRunnable 上, 拿不到 Reactor Context), 与 GraphStreamRunner 同款机制;
 * modelId 进一步透传给子代理内部 LLM(方案 C).
 */
@Slf4j
@Component
public class MultiAgentManager {

    private final ModelRouter modelRouter;
    private final ToolRegistry toolRegistry;
    private final ChatMemory chatMemory;
    private final AgentConfig config;
    private final AskUserToolCallback askUserToolCallback;
    private final CalculatorTool calculatorTool;
    private final WeatherSubAgent weatherSubAgent;
    private final KnowledgeSubAgent knowledgeSubAgent;
    private final TripPlanningTool tripPlanningTool;

    /** 每会话并发控制, 拒绝同一 conversationId 的并行请求(与 ReactAgent 一致) */
    private final ConcurrentHashMap<String, AtomicBoolean> conversationLocks = new ConcurrentHashMap<>();

    /** 单轮并行调度子代理的最大并发数(方案 C, 与 ReactAgent 工具执行一致) */
    private static final int MAX_CONCURRENT_TOOLS = 4;

    public MultiAgentManager(ModelRouter modelRouter, ToolRegistry toolRegistry,
                             ChatMemory chatMemory, AgentConfig config,
                             AskUserToolCallback askUserToolCallback,
                             CalculatorTool calculatorTool,
                             WeatherSubAgent weatherSubAgent,
                             KnowledgeSubAgent knowledgeSubAgent,
                             TripPlanningTool tripPlanningTool) {
        this.modelRouter = modelRouter;
        this.toolRegistry = toolRegistry;
        this.chatMemory = chatMemory;
        this.config = config;
        this.askUserToolCallback = askUserToolCallback;
        this.calculatorTool = calculatorTool;
        this.weatherSubAgent = weatherSubAgent;
        this.knowledgeSubAgent = knowledgeSubAgent;
        this.tripPlanningTool = tripPlanningTool;
    }

    /**
     * 流式执行 Multi-Agent 调度
     *
     * @param userInput      用户消息
     * @param conversationId 会话 ID
     * @param traceId        链路追踪 ID
     * @param modelId        模型业务 ID(null 使用注册表默认)
     */
    public Flux<StreamChunk> stream(String userInput, String conversationId, String traceId,
                                    String modelId) {
        long startMs = System.currentTimeMillis();

        AtomicBoolean inProgress = conversationLocks.computeIfAbsent(
                conversationId, k -> new AtomicBoolean(false));
        if (!inProgress.compareAndSet(false, true)) {
            log.warn("[traceId={}] 会话[{}] 已在处理中, 拒绝并发请求", traceId, conversationId);
            return Flux.just(StreamChunk.error(conversationId,
                    "当前会话正在处理中，请等待完成后再发新消息",
                    System.currentTimeMillis() - startMs));
        }

        Sinks.Many<StreamChunk> sink = Sinks.many().unicast().onBackpressureBuffer();

        Mono.fromRunnable(() -> runLoopAndEmit(userInput, conversationId, traceId, modelId,
                        sink, startMs))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null,
                        err -> {
                            log.error("[traceId={}] [MultiAgent] 异步异常: {}", traceId,
                                    err.getMessage(), err);
                            sink.tryEmitNext(StreamChunk.error(conversationId,
                                    "Multi-Agent 执行异常: " + err.getMessage(),
                                    System.currentTimeMillis() - startMs));
                            sink.tryEmitComplete();
                        },
                        () -> { /* sink 已在 runLoopAndEmit 中完成 */ });

        return sink.asFlux()
                .doFinally(signalType -> {
                    inProgress.set(false);
                    conversationLocks.remove(conversationId);
                });
    }

    /** 主管可用工具: 子代理 + 反问 + 计算器 */
    private ToolCallback[] managerTools() {
        return new ToolCallback[]{
                weatherSubAgent, knowledgeSubAgent, tripPlanningTool,
                askUserToolCallback, calculatorTool
        };
    }

    /** 主管工具名索引(子代理/askUser/calculator 都在内), 执行时按名解析 */
    private Map<String, ToolCallback> managerToolIndex() {
        Map<String, ToolCallback> map = new HashMap<>();
        for (ToolCallback t : managerTools()) {
            map.put(t.getToolDefinition().name(), t);
        }
        return map;
    }

    /**
     * 同步 ReAct 调度循环, 边执行边把 SSE 事件推入 sink
     */
    private void runLoopAndEmit(String userInput, String conversationId, String traceId,
                                String modelId, Sinks.Many<StreamChunk> sink, long startMs) {
        AgentState state = new AgentState(conversationId, MultiAgentPrompts.MANAGER_SYSTEM_PROMPT, userInput);
        try {
            // 加载历史记忆(续聊), 与 ReactAgent 相同语义
            List<Message> history = chatMemory.load(conversationId);
            if (!history.isEmpty()) {
                history.forEach(msg -> state.getMessages().add(state.getMessages().size() - 1, msg));
            }
            state.setLoadedMessageCount(history.size());

            ChatModel managerModel = modelRouter.route(modelId);
            String modelName = (modelId == null || modelId.isBlank())
                    ? null : modelRouter.resolveModelName(modelId);
            ToolCallback[] tools = managerTools();

            AssistantMessage finalResponse = null;
            int maxIterations = config.getMaxIterations();

            for (int i = 0; i < maxIterations; i++) {
                log.info("[traceId={}] [MultiAgent] 会话[{}] 第 {}/{} 轮调度",
                        traceId, conversationId, i + 1, maxIterations);

                AssistantMessage resp = managerModel.call(state.getFullMessages(), modelName, tools);
                if (resp == null) {
                    sink.tryEmitNext(StreamChunk.error(conversationId,
                            "LLM 返回空响应", System.currentTimeMillis() - startMs));
                    break;
                }

                // 发射主管本轮思考文字(修复 1: 调度过程可读, 与 ReAct 的 thinking_token 对齐)
                String thought = resp.getText();
                if (thought != null && !thought.isBlank()) {
                    sink.tryEmitNext(StreamChunk.thinkingToken(
                            thought, conversationId, "第 " + (i + 1) + " 轮调度"));
                }

                if (!resp.hasToolCalls()) {
                    // 无工具调用 → 最终答案
                    finalResponse = resp;
                    state.addReasoningResult(resp);
                    break;
                }

                // 有工具调用 → 注入历史 + 并行调度子代理(方案 C)
                state.addReasoningResult(resp);
                sink.tryEmitNext(StreamChunk.toolCallStart(conversationId,
                        "开始调度子代理 (第 " + (i + 1) + " 轮)"));

                for (AssistantMessage.ToolCall tc : resp.getToolCalls()) {
                    sink.tryEmitNext(StreamChunk.toolCall(tc.id(), tc.name(),
                            tc.arguments(), conversationId));
                }
                // 方案 C 并行调度: 同轮多个子代理 flatMap 并行执行(boundedElastic),
                // 执行期间各自经 progress 回调实时上抛完整生命周期事件到 sink;
                // 执行与历史注入分离(executeTool 不碰 state), 全部完成后按 LLM 原序注入,
                // 保证消息历史顺序与并发写安全.
                Map<String, ToolExecutionOutcome> outcomeById = new ConcurrentHashMap<>();
                Flux.fromIterable(resp.getToolCalls())
                        .flatMap(tc -> Mono.fromCallable(() -> {
                                    ToolExecutionOutcome o = executeTool(tc, conversationId,
                                            traceId, modelId, sink);
                                    outcomeById.put(tc.id(), o);
                                    return o;
                                })
                                .subscribeOn(Schedulers.boundedElastic()),
                                Math.min(resp.getToolCalls().size(), MAX_CONCURRENT_TOOLS))
                        .blockLast();
                for (AssistantMessage.ToolCall tc : resp.getToolCalls()) {
                    ToolExecutionOutcome outcome = outcomeById.get(tc.id());
                    state.getMessages().add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    tc.id(), tc.name(), outcome.response())))
                            .build());
                    sink.tryEmitNext(renderOutcomeEvent(state, outcome));
                }
                sink.tryEmitNext(StreamChunk.iterationSeparator(conversationId,
                        "第 " + (i + 1) + " 轮调度完成"));
            }

            // 收尾: 保存历史 + 发射 final/error
            chatMemory.save(conversationId, state.getNewMessages(), traceId);
            if (finalResponse != null && finalResponse.getText() != null) {
                sink.tryEmitNext(StreamChunk.final_(conversationId, finalResponse.getText(),
                        System.currentTimeMillis() - startMs));
            } else {
                String lastContent = state.getMessages().isEmpty()
                        ? "" : state.getMessages().get(state.getMessages().size() - 1).getText();
                sink.tryEmitNext(StreamChunk.error(conversationId,
                        "达到最大调度轮次(" + maxIterations + ")仍未得出最终答案",
                        System.currentTimeMillis() - startMs));
            }
        } catch (Exception e) {
            log.error("[traceId={}] [MultiAgent] 执行异常", traceId, e);
            chatMemory.save(conversationId, state.getNewMessages(), traceId);
            sink.tryEmitNext(StreamChunk.error(conversationId,
                    "Multi-Agent 执行异常: " + e.getMessage(),
                    System.currentTimeMillis() - startMs));
        } finally {
            sink.tryEmitComplete();
        }
    }

    /** 单次工具调用的执行结果 */
    private record ToolExecutionOutcome(AssistantMessage.ToolCall call, String response,
                                        boolean error) {
    }

    /**
     * 执行单个工具: 透明化工具(子代理 / tripPlanner)走带上下文的入口并透传
     * node_progress 进度; 其他工具(askUser/calculator)直接调用.
     *
     * <p>方案 C 并行化: 本方法<strong>不修改 state</strong>(执行与历史注入分离),
     * 只返回执行结果, 由调用方在全部工具完成后按 LLM 原序注入消息历史 —
     * 避免多个子代理并行执行时并发写 ArrayList 造成数据竞争.
     *
     * @param modelId 模型业务 ID(透传给子代理内部 LLM; null = 注册表默认)
     */
    private ToolExecutionOutcome executeTool(AssistantMessage.ToolCall tc,
                                             String conversationId, String traceId,
                                             String modelId, Sinks.Many<StreamChunk> sink) {
        // 子代理不在 ToolRegistry 中(只注册进主管工具集), 先查主管工具索引, 再回退注册表
        ToolCallback tool = managerToolIndex().get(tc.name());
        if (tool == null) {
            tool = toolRegistry.getByName(tc.name());
        }
        if (tool == null) {
            String err = "错误: 未知工具 '" + tc.name() + "'";
            log.warn("[traceId={}] [MultiAgent] 未知工具: {}", traceId, tc.name());
            return new ToolExecutionOutcome(tc, err, true);
        }

        long t0 = System.currentTimeMillis();
        boolean askUser = "askUser".equals(tc.name());
        try {
            if (askUser) {
                AskUserTool.setContext(conversationId, traceId == null ? "" : traceId);
            }
            String result;
            if (tool instanceof TransparentAgentTool transparent) {
                // 子代理 / tripPlanner: 显式传入 modelId + 进度回调, 完整生命周期实时上抛
                result = transparent.callTransparent(tc.arguments(), conversationId, traceId,
                        modelId, chunk -> sink.tryEmitNext(chunk));
            } else {
                result = tool.call(tc.arguments());
            }
            long duration = System.currentTimeMillis() - t0;
            log.info("[traceId={}] [MultiAgent] 工具 {} 完成, 耗时 {}ms, 结果长度={}",
                    traceId, tc.name(), duration, result.length());
            return new ToolExecutionOutcome(tc, result, false);
        } catch (Exception e) {
            String err = "工具执行失败: " + e.getMessage();
            log.warn("[traceId={}] [MultiAgent] 工具 {} 异常: {}", traceId, tc.name(), e.getMessage());
            return new ToolExecutionOutcome(tc, err, true);
        } finally {
            if (askUser) {
                AskUserTool.clearContext();
            }
        }
    }

    private StreamChunk renderOutcomeEvent(AgentState state, ToolExecutionOutcome o) {
        if (o.error()) {
            return StreamChunk.toolError(o.call().id(), o.call().name(),
                    o.response(), state.getConversationId());
        }
        return StreamChunk.toolResult(o.call().id(), o.call().name(),
                truncate(o.response(), 300), state.getConversationId());
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
