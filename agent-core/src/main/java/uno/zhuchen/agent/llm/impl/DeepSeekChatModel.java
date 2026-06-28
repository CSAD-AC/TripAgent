package uno.zhuchen.agent.llm.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import uno.zhuchen.agent.llm.ChatModel;

import java.util.Arrays;
import java.util.List;

/**
 * LLM 调用实现 — 基于 Spring AI OpenAI 客户端指向 DeepSeek API
 *
 * DeepSeek 兼容 OpenAI 接口格式，OpenAiChatModel 的消息序列化
 * 比 DeepSeekChatModel（v1.1.2)更准确，能正确处理 ToolResponseMessage。
 *
 * 使用 spring.ai.openai.* 配置参数，通过 Spring profile "deepseek" 激活。
 */
public class DeepSeekChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatModel.class);

    private final org.springframework.ai.chat.model.ChatModel chatModel;

    /** LLM 生成的最大 token 数（deepseek-chat 上下文 64K） */
    private static final int MAX_TOKENS = 16384;

    public DeepSeekChatModel(org.springframework.ai.chat.model.ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 构建 OpenAiChatOptions
     *
     * 设置 internalToolExecutionEnabled=false 以代理工具调用到客户端，
     * 并注入 ToolCallback。
     */
    private OpenAiChatOptions buildChatOptions(ToolCallback... tools) {
        OpenAiChatOptions chatOptions = new OpenAiChatOptions();
        chatOptions.setInternalToolExecutionEnabled(false);
        chatOptions.setMaxTokens(MAX_TOKENS);

        if (tools != null && tools.length > 0) {
            chatOptions.setToolCallbacks(Arrays.asList(tools));
        }

        return chatOptions;
    }

    @Override
    public AssistantMessage call(List<Message> messages, ToolCallback... tools) {
        log.debug("DeepSeek 同步调用, messages 数量: {}", messages.size());

        try {
            OpenAiChatOptions chatOptions = buildChatOptions(tools);

            Prompt prompt = Prompt.builder()
                    .messages(messages)
                    .chatOptions(chatOptions)
                    .build();

            ChatResponse response = chatModel.call(prompt);

            if (response == null || response.getResult() == null) {
                log.warn("DeepSeek 返回空响应");
                return new AssistantMessage("抱歉，我没有得到有效的回复。");
            }

            AssistantMessage result = (AssistantMessage) response.getResult().getOutput();
            log.debug("DeepSeek 响应完成, hasToolCalls={}, text长度={}",
                    result.hasToolCalls(),
                    result.getText() != null ? result.getText().length() : 0);

            return result;

        } catch (Exception e) {
            log.error("DeepSeek 同步调用异常", e);
            return new AssistantMessage("调用 DeepSeek LLM 时发生错误: " + e.getMessage());
        }
    }

    @Override
    public Flux<ChatResponse> stream(List<Message> messages, ToolCallback... tools) {
        log.debug("DeepSeek 流式调用, messages 数量: {}", messages.size());

        OpenAiChatOptions chatOptions = buildChatOptions(tools);

        Prompt prompt = Prompt.builder()
                .messages(messages)
                .chatOptions(chatOptions)
                .build();

        return chatModel.stream(prompt)
                .doOnError(WebClientResponseException.class, e -> {
                    log.error("DeepSeek API 返回 HTTP {} 错误, body:\n{}",
                            e.getStatusCode(), e.getResponseBodyAsString());
                });
    }
}
