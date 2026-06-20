package uno.zhuchen.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.config.AgentConfig;
import uno.zhuchen.agent.domain.dto.ChatDTO;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.memory.ChatMemory;
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

                        try {
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
     * 流式调用：先同步执行 ReAct 循环（含工具调用），再用缓存的最终文本分块推送 SSE
     * <p>
     * 方案 A（当前采用）：
     *   1. 同步运行 ReAct 循环（与 call() 逻辑相同），其间通过 FluxSink 推送工具调用事件
     *   2. 循环结束后将最终答案切成小块，逐块推送 thinking 事件
     *   3. 最终推送 final 事件（含完整答案和耗时）
     * <p>
     * SSE 事件序列：
     *   tool_call → tool_result → tool_call → tool_result → thinking → ... → final
     * <p>
     * 后续迭代为方案 B（全程端到端流式）：见 streaming-tool-call-plan.md
     */
    public Flux<StreamChunk> stream(String userInput, String conversationId) {
        long start = System.currentTimeMillis();

        return Flux.<StreamChunk>create(sink -> {
            try {
                // 1. 初始化 Agent 状态
                AgentState state = new AgentState(conversationId, config.getSystemPrompt(), userInput);
                log.debug("Agent[{}] 开始流式 ReAct 循环", state.getConversationId());

                // 2. 加载历史记忆
                List<Message> history = chatMemory.load(state.getConversationId());
                if (!history.isEmpty()) {
                    log.debug("Agent[{}] 加载 {} 条历史消息", state.getConversationId(), history.size());
                    history.forEach(msg -> state.getMessages().add(state.getMessages().size() - 1, msg));
                }

                // 3. 获取所有可用工具
                ToolCallback[] allTools = toolRegistry.getAll();
                log.debug("Agent[{}] 可用工具数: {}, 工具列表: {}",
                        state.getConversationId(),
                        allTools.length,
                        java.util.Arrays.stream(allTools)
                                .map(t -> t.getToolDefinition().name())
                                .collect(java.util.stream.Collectors.toList()));

                // 4. ReAct 循环（同步执行，含工具调用）
                AssistantMessage finalResponse = null;

                for (int i = 0; i < config.getMaxIterations(); i++) {
                    log.debug("Agent[{}] 流式第 {}/{} 轮迭代", state.getConversationId(),
                            i + 1, config.getMaxIterations());

                    // --- Reason: 调用 LLM ---
                    AssistantMessage response = chatModel.call(state.getFullMessages(), allTools);

                    // --- Decide: 检查是否有工具调用 ---
                    if (response.hasToolCalls()) {
                        log.debug("Agent[{}] 检测到 {} 个工具调用请求",
                                state.getConversationId(), response.getToolCalls().size());

                        // 将 LLM 的 toolCalls 请求加入消息历史
                        state.addReasoningResult(response);

                        // --- Act: 执行工具，同时推送 SSE 事件 ---
                        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

                        for (AssistantMessage.ToolCall toolCall : response.getToolCalls()) {
                            // 推送 tool_call 事件
                            sink.next(StreamChunk.toolCall(
                                    toolCall.name(), toolCall.arguments(), state.getConversationId()));

                            ToolCallback tool = toolRegistry.getByName(toolCall.name());
                            if (tool == null) {
                                log.warn("Agent[{}] 未知工具: {}", state.getConversationId(), toolCall.name());
                                String errorMsg = "错误: 未知工具 '" + toolCall.name() + "'";
                                toolResponses.add(new ToolResponseMessage.ToolResponse(
                                        toolCall.id(), toolCall.name(), errorMsg));
                                sink.next(StreamChunk.toolResult(
                                        toolCall.name(), errorMsg, state.getConversationId()));
                                continue;
                            }

                            log.debug("Agent[{}] 执行工具: {} (id={})",
                                    state.getConversationId(), toolCall.name(), toolCall.id());
                            try {
                                String result = tool.call(toolCall.arguments());
                                String summary = truncate(result, 300);
                                toolResponses.add(new ToolResponseMessage.ToolResponse(
                                        toolCall.id(), toolCall.name(), result));
                                // 推送 tool_result 事件（仅摘要，避免推超大 JSON）
                                sink.next(StreamChunk.toolResult(
                                        toolCall.name(), summary, state.getConversationId()));
                            } catch (Exception e) {
                                log.error("Agent[{}] 工具 {} 执行异常",
                                        state.getConversationId(), toolCall.name(), e);
                                toolResponses.add(new ToolResponseMessage.ToolResponse(
                                        toolCall.id(), toolCall.name(),
                                        "工具执行失败: " + e.getMessage()));
                                sink.next(StreamChunk.toolResult(
                                        toolCall.name(), "工具执行失败: " + e.getMessage(),
                                        state.getConversationId()));
                            }
                        }

                        // 将工具响应注入消息历史，继续下一轮迭代
                        state.getMessages().add(ToolResponseMessage.builder()
                                .responses(toolResponses)
                                .build());
                        continue;
                    }

                    // --- 无工具调用 → 这就是最终答案 ---
                    finalResponse = response;
                    state.addReasoningResult(response);
                    log.debug("Agent[{}] 第 {} 轮得到最终答案", state.getConversationId(), i + 1);
                    break;
                }

                long duration = System.currentTimeMillis() - start;

                if (finalResponse != null) {
                    // 5. 保存对话历史
                    chatMemory.save(state.getConversationId(), state.getMessages());

                    // 6. 将最终答案分块推送（模拟流式效果）
                    String answer = finalResponse.getText();
                    int chunkSize = 30;
                    int len = answer.length();
                    int pos = 0;
                    while (pos < len) {
                        int end = Math.min(pos + chunkSize, len);
                        // 尝试在标点处断句（让前端渲染更自然）
                        if (end < len) {
                            int breakAt = lastPunctuationIndex(answer, pos, end);
                            if (breakAt > pos) {
                                end = breakAt + 1;
                            }
                        }
                        sink.next(StreamChunk.thinking(answer.substring(pos, end), state.getConversationId()));
                        pos = end;
                    }

                    // 7. 推送 final 事件
                    sink.next(StreamChunk.final_(state.getConversationId(), answer, duration));
                } else {
                    // 达到最大迭代次数
                    sink.next(StreamChunk.error(state.getConversationId(),
                            "未能在最大迭代次数内得出最终答案", duration));
                }

                sink.complete();

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                log.error("Agent[{}] 流式执行异常", conversationId, e);
                sink.next(StreamChunk.error(conversationId, e.getMessage(), duration));
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 在 [start, end) 范围内找最后一个标点符号的位置
     */
    private static int lastPunctuationIndex(String text, int start, int end) {
        for (int i = end - 1; i > start; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '，' || c == '！' || c == '？'
                    || c == '；' || c == '\n' || c == '.'
                    || c == ',' || c == '!' || c == '?') {
                return i;
            }
        }
        return -1;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
