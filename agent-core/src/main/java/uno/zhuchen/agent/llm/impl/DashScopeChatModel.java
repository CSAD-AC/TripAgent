package uno.zhuchen.agent.llm.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import uno.zhuchen.agent.llm.ChatModel;

import java.util.List;

/**
 * DashScope 实现的 ChatModel
 *
 * 直接调用 DashScope ChatModel 的 call/stream 方法，
 * 完全绕过 ChatClient 的 advisor 链。
 */
public class DashScopeChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatModel.class);

    private final org.springframework.ai.chat.model.ChatModel dashScopeChatModel;

    public DashScopeChatModel(org.springframework.ai.chat.model.ChatModel dashScopeChatModel) {
        this.dashScopeChatModel = dashScopeChatModel;
    }

    @Override
    public AssistantMessage call(List<Message> messages, ToolCallback... tools) {
        log.debug("LLM 同步调用, messages 数量: {}", messages.size());

        try {
            Prompt prompt = Prompt.builder()
                    .messages(messages)
                    .chatOptions(ToolCallingChatOptions.builder()
                            .toolCallbacks(tools)
                            .internalToolExecutionEnabled(false)
                            .build())
                    .build();

            ChatResponse response = dashScopeChatModel.call(prompt);

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
    public Flux<ChatResponse> stream(List<Message> messages, ToolCallback... tools) {
        log.debug("LLM流式调用, messages 数量: {}", messages.size());

        Prompt prompt = Prompt.builder()
                .messages(messages)
                .chatOptions(ToolCallingChatOptions.builder()
                        .toolCallbacks(tools)
                        .internalToolExecutionEnabled(false)
                        .build())
                .build();

        // 直接调用 DashScope ChatModel 的流式方法，完全绕过 ChatClient 的 advisor 链
        return dashScopeChatModel.stream(prompt);
    }
}
