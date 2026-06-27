package uno.zhuchen.agent.agent;

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
import uno.zhuchen.agent.domain.dto.ChatDTO;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.memory.ChatMemory;
import uno.zhuchen.agent.tool.AskUserTool;
import uno.zhuchen.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

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
 */
public class ReactAgent {

    private static final Logger log = LoggerFactory.getLogger(ReactAgent.class);

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final AgentConfig config;
    private final ToolRegistry toolRegistry;

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
                        try {
                            AskUserTool.setConversationId(state.getConversationId());
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
                            AskUserTool.clearConversationId();
                        }
                    }

                    // 将工具响应注入消息历史 → 回到 Reason 步骤开始下一轮迭代
                    state.getMessages().add(ToolResponseMessage.builder()
                            .responses(toolResponses)
                            .build());
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
     */
    public Flux<StreamChunk> stream(String userInput, String conversationId) {
        long start = System.currentTimeMillis();
        AgentState state = new AgentState(conversationId, config.getSystemPrompt(), userInput);

        // 加载历史记忆（非阻塞操作）
        List<Message> history = chatMemory.load(state.getConversationId());
        if (!history.isEmpty()) {
            history.forEach(msg -> state.getMessages().add(state.getMessages().size() - 1, msg));
        }

        ToolCallback[] allTools = toolRegistry.getAll();

        return nextIteration(state, allTools, 0, start);
    }

    /**
     * 递归执行一轮 ReAct 迭代
     *
     * 每轮分为两个阶段：
     *   阶段 1 — LLM 流式输出：实时推送 thinking_token，同时累计文本和工具调用
     *   阶段 2 — 工具执行：若本轮产生工具调用，在 boundedElastic 上执行同步工具调用后递归下一轮
     */
    private Flux<StreamChunk> nextIteration(AgentState state, ToolCallback[] tools,
                                             int iteration, long start) {
        if (iteration >= config.getMaxIterations()) {
            return Flux.just(StreamChunk.error(state.getConversationId(),
                    "未能在最大迭代次数内得出最终答案", System.currentTimeMillis() - start));
        }

        int iterNum = iteration + 1;
        StringBuilder thoughtBuffer = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();

        // ========== 阶段 1：LLM 流式输出 ==========
        Flux<StreamChunk> thinkingFlux = chatModel.stream(state.getFullMessages(), tools)
                .doOnNext(chatResponse -> {
                    // 副作用：累计文本和工具调用（flatMap 执行后才完成累计）
                    AssistantMessage msg = (AssistantMessage) chatResponse.getResult().getOutput();
                    if (msg.getText() != null && !msg.getText().isEmpty()) {
                        thoughtBuffer.append(msg.getText());
                    }
                    if (msg.hasToolCalls() && msg.getToolCalls() != null) {
                        toolCalls.addAll(msg.getToolCalls());
                    }
                })
                .flatMap(chatResponse -> {
                    // 实时推送 thinking_token
                    AssistantMessage msg = (AssistantMessage) chatResponse.getResult().getOutput();
                    if (msg.getText() != null && !msg.getText().isEmpty()) {
                        return Mono.just(StreamChunk.thinkingToken(
                                msg.getText(), state.getConversationId(), "迭代" + iterNum));
                    }
                    return Mono.empty();
                });

        // ========== 阶段 2：工具执行或最终答案 ==========
        // 使用 Mono.fromCallable + subscribeOn 确保 blocking 工具调用只执行一次，
        // 避免 Flux.defer + subscribeOn 被递归 concatWith 多次订阅导致并发执行。
        Flux<StreamChunk> continuationFlux = Mono.fromCallable(() -> {
            // 累计完成，组装 AssistantMessage
            // 创建 toolCalls 快照，避免 doOnNext（reactor 线程）与遍历（boundedElastic 线程）的并发修改
            List<AssistantMessage.ToolCall> toolCallsSnapshot = List.copyOf(toolCalls);

            AssistantMessage response = AssistantMessage.builder()
                    .content(thoughtBuffer.toString())
                    .toolCalls(toolCallsSnapshot.isEmpty() ? List.of() : toolCallsSnapshot)
                    .build();
            state.addReasoningResult(response);

            if (toolCallsSnapshot.isEmpty()) {
                // 无工具调用 → 最终答案
                chatMemory.save(state.getConversationId(), state.getMessages());
                return Flux.just(StreamChunk.final_(
                        state.getConversationId(), thoughtBuffer.toString(),
                        System.currentTimeMillis() - start));
            }

            // 执行工具（去重：相同 name + 相同 arguments 只执行第一次）
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            List<StreamChunk> toolEvents = new ArrayList<>();
            java.util.Set<String> seenToolCalls = new java.util.HashSet<>();

            toolEvents.add(StreamChunk.toolCallStart(state.getConversationId(), "开始执行工具调用"));

            for (AssistantMessage.ToolCall toolCall : toolCallsSnapshot) {
                // 去重 key: name + arguments
                String toolKey = toolCall.name() + "::" + toolCall.arguments();
                if (!seenToolCalls.add(toolKey)) {
                    log.warn("Agent[{}] 跳过重复工具调用: {} (同 args)", state.getConversationId(), toolCall.name());
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(),
                            "重复调用已跳过,使用第一次调用的结果"));
                    toolEvents.add(StreamChunk.toolResult(toolCall.name(),
                            "重复调用已跳过", state.getConversationId()));
                    continue;
                }

                toolEvents.add(StreamChunk.toolCall(
                        toolCall.name(), toolCall.arguments(), state.getConversationId()));

                ToolCallback tool = toolRegistry.getByName(toolCall.name());
                if (tool == null) {
                    String errorMsg = "未知工具: " + toolCall.name();
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), errorMsg));
                    toolEvents.add(StreamChunk.toolError(toolCall.name(), errorMsg, state.getConversationId()));
                    continue;
                }

                // 反问工具需要 conversationId 上下文
                try {
                    AskUserTool.setConversationId(state.getConversationId());
                    long toolStart = System.currentTimeMillis();
                    String result = tool.call(toolCall.arguments());
                    long toolDuration = System.currentTimeMillis() - toolStart;
                    log.debug("Agent[{}] 工具 {} 执行完成, 耗时={}ms, 结果长度={}",
                            state.getConversationId(), toolCall.name(), toolDuration, result.length());
                    String summary = truncate(result, 300);
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), result));
                    toolEvents.add(StreamChunk.toolResult(toolCall.name(), summary, state.getConversationId()));
                } catch (Exception e) {
                    String errorMsg = "工具执行失败: " + e.getMessage();
                    log.warn("Agent[{}] 工具 {} 执行异常: {}",
                                state.getConversationId(), toolCall.name(), errorMsg);
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), errorMsg));
                } finally {
                    AskUserTool.clearConversationId();
                }
            }

            // 将工具响应注入消息历史，继续下一轮
            state.getMessages().add(ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build());

            toolEvents.add(StreamChunk.iterationSeparator(state.getConversationId(),
                    "第" + iterNum + "轮完成"));

            // 递归下一轮（返回 Flux，由 flatMapMany 展开）
            return Flux.fromIterable(toolEvents)
                    .concatWith(nextIteration(state, tools, iteration + 1, start));
        }).subscribeOn(Schedulers.boundedElastic())
          .flatMapMany(flux -> flux);

        return thinkingFlux.concatWith(continuationFlux);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
