package uno.zhuchen.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uno.zhuchen.agent.domain.dto.ChatDTO;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.memory.ChatMemory;

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

    public ReactAgent(ChatModel chatModel, ChatMemory chatMemory, AgentConfig config) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.config = config;
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
                AssistantMessage response = chatModel.call(state.getFullMessages());
                String content = response.getText();

                // 记录推理步骤
                reasoningSteps.add("Step " + (i + 1) + ": " + truncate(content, 200));

                // --- Decide: 检查是否有工具调用 ---
                if (response.hasToolCalls()) {
                    // 有工具调用（未来扩展）：记录到状态，继续循环
                    log.debug("Agent[{}] 检测到工具调用请求: {}",
                            state.getConversationId(), response.getToolCalls());
                    state.addReasoningResult(response);
                    // TODO: 执行工具 → 观察结果 → 注入下一轮
                    // 当前阶段跳过工具执行，将 tool calls 视为中间推理
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
     * 流式调用：执行 ReAct 循环，逐 token 推送 SSE 事件
     *
     * 与 {@link #call(String, String)} 共享相同的 AgentState 初始化和记忆加载逻辑，
     * 区别在于 LLM 调用方式从同步转为流式——每收到一个 token 立即通过 SSE 推送给前端。
     *
     * 事件序列（正常完成）：
     *   token → token → ... → token → done
     * 异常时：
     *   token → ... → error
     *
     */
    public Flux<StreamChunk> stream(String userInput, String conversationId) {
        long start = System.currentTimeMillis();

        return Flux.defer(() -> {
            // 1. 初始化 Agent 状态
            AgentState state = new AgentState(conversationId, config.getSystemPrompt(), userInput);
            log.debug("Agent[{}] 开始流式 ReAct 循环", state.getConversationId());

            // 2. 加载历史记忆
            List<Message> history = chatMemory.load(state.getConversationId());
            if (!history.isEmpty()) {
                log.debug("Agent[{}] 加载 {} 条历史消息", state.getConversationId(), history.size());
                history.forEach(msg ->
                        state.getMessages().add(state.getMessages().size() - 1, msg));
            }

            // 3. 累积完整回答（用于记忆保存）
            StringBuilder fullAnswer = new StringBuilder();

            return chatModel.stream(state.getFullMessages())
                    .doOnNext(token -> {
                        fullAnswer.append(token);
                        log.trace("Agent[{}] token: {}", state.getConversationId(),
                                truncate(token, 50));
                    })
                    .map(token -> StreamChunk.thinking(token, state.getConversationId()))
                    .doOnComplete(() -> {
                        // 4. 流结束：保存对话历史
                        String answer = fullAnswer.toString();
                        state.addReasoningResult(new AssistantMessage(answer));
                        chatMemory.save(state.getConversationId(), state.getMessages());
                        log.debug("Agent[{}] 流式完成, answer长度={}",
                                state.getConversationId(), answer.length());
                    })
                    .concatWith(Mono.fromCallable(() -> {
                        long duration = System.currentTimeMillis() - start;
                        return StreamChunk.final_(
                                state.getConversationId(),
                                fullAnswer.toString(),
                                duration);
                    }))
                    .onErrorResume(e -> {
                        long duration = System.currentTimeMillis() - start;
                        log.error("Agent[{}] 流式执行异常", state.getConversationId(), e);
                        return Mono.just(StreamChunk.error(
                                state.getConversationId(),
                                e.getMessage(),
                                duration));
                    });
        });
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
