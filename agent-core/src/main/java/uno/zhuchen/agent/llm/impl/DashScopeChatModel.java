package uno.zhuchen.agent.llm.impl;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import uno.zhuchen.agent.llm.ChatModel;

import java.util.Arrays;
import java.util.List;

/**
 * DashScope 实现的 ChatModel
 *
 * 直接调用 DashScope ChatModel 的 call/stream 方法，
 * 完全绕过 ChatClient 的 advisor 链。
 *
 * 使用 DashScopeChatOptions.withMultiModel(true) 启用 qwen3 多模态端点，
 * 同时保留工具调用能力。
 */
public class DashScopeChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatModel.class);

    private final org.springframework.ai.chat.model.ChatModel dashScopeChatModel;


    private final boolean multiModel;

    public DashScopeChatModel(org.springframework.ai.chat.model.ChatModel dashScopeChatModel,
                              boolean multiModel) {
        this.dashScopeChatModel = dashScopeChatModel;
        this.multiModel = multiModel;
    }

    /** LLM 生成的最大 token 数，防止长 JSON 被截断 */
    private static final int MAX_TOKENS = 8192;

    /**
     * 构建统一的 ChatOptions
     */
    private DashScopeChatOptions buildChatOptions(ToolCallback... tools) {
        DashScopeChatOptions chatOptions = new DashScopeChatOptions();
        chatOptions.setInternalToolExecutionEnabled(false);
        chatOptions.setMaxTokens(MAX_TOKENS);

        if (tools != null && tools.length > 0) {
            chatOptions.setToolCallbacks(Arrays.asList(tools));
        }

        if (multiModel) {
            chatOptions.setMultiModel(true);
        }

        return chatOptions;
    }


    @Override
    public AssistantMessage call(List<Message> messages, ToolCallback... tools) {
        log.debug("LLM 同步调用, messages 数量: {}", messages.size());

        try {
            DashScopeChatOptions chatOptions = buildChatOptions(tools);

            Prompt prompt = Prompt.builder()
                    .messages(messages)
                    .chatOptions(chatOptions)
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

        DashScopeChatOptions chatOptions = buildChatOptions(tools);

        Prompt prompt = Prompt.builder()
                .messages(messages)
                .chatOptions(chatOptions)
                .build();

        return dashScopeChatModel.stream(prompt);
    }
}
