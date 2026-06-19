package uno.zhuchen.agent.llm.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import uno.zhuchen.agent.llm.ChatModel;

import java.util.List;

/**
 * DashScope 实现的 ChatModel
 *
 * <p>基于 Spring AI 的 ChatClient 封装，对接阿里通义系列模型。</p>
 */
public class DashScopeChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatModel.class);

    private final ChatClient chatClient;

    public DashScopeChatModel(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public AssistantMessage call(List<Message> messages) {
        log.debug("LLM 调用, messages 数量: {}", messages.size());

        org.springframework.ai.chat.model.ChatResponse response = chatClient.prompt()
                .messages(messages)
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
    }
}
