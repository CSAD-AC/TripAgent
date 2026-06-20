package uno.zhuchen.agent.llm.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import uno.zhuchen.agent.llm.ChatModel;

import java.util.List;

/**
 * DashScope 实现的 ChatModel
 *
 * 基于 Spring AI 的 ChatClient 封装，对接阿里通义系列模型。
 */
public class DashScopeChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatModel.class);

    private final ChatClient chatClient;


    public DashScopeChatModel(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public AssistantMessage call(List<Message> messages, ToolCallback... tools) {
        log.debug("LLM 同步调用, messages 数量: {}", messages.size());

        try {
            ChatResponse response = chatClient.prompt()
                    .messages(messages)
                    .toolCallbacks(tools)
                    .options(ToolCallingChatOptions.builder()
                            .internalToolExecutionEnabled(false)
                            .build())
                    .call()
                    .chatResponse();

            if (response == null || response.getResult() == null) {
                log.warn("LLM 返回空响应");
                return new AssistantMessage("抱歉，我没有得到有效的回复。");
            }

            AssistantMessage result = (AssistantMessage) response.getResult().getOutput();
            log.debug("LLM 响应完成, hasToolCalls={}, text长度={}",
                    result.hasToolCalls(),
                    result.getText() != null ? result.getText().length() : 0);

            return result;

        } catch (Exception e) {
            log.error("LLM 同步调用异常", e);
            return new AssistantMessage("调用 LLM 时发生错误: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> stream(List<Message> messages, ToolCallback... tools) {
        log.debug("LLM 流式调用, messages 数量: {}", messages.size());

        try {
            return chatClient.prompt()
                    .messages(messages)
                    .toolCallbacks(tools)
                    .options(ToolCallingChatOptions.builder()
                            .internalToolExecutionEnabled(false)
                            .build())
                    .stream()
                    .content()
                    .doOnNext(token -> log.trace("LLM token: {}", token))
                    .doOnComplete(() -> log.debug("LLM 流式响应完成"))
                    .doOnError(e -> log.error("LLM 流式响应异常", e));

        } catch (Exception e) {
            log.error("LLM 流式调用异常", e);
            return Flux.just("调用 LLM 流式接口时发生错误: " + e.getMessage());
        }
    }
}
