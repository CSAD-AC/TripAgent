package uno.zhuchen.agent.llm.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import uno.zhuchen.agent.llm.ChatModel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** LLM 生成的最大 token 数 */
    @Value("${spring.ai.openai.chat.options.max-tokens:16384}")
    private Integer maxTokens;

    /** 思考模式开关 (enabled/disabled)，从 application.yml deepseek.thinking 注入 */
    @Value("${spring.ai.openai.deepseek.thinking:disabled}")
    private String thinking;

    /** 思考强度 (high/max)，从 application.yml deepseek.reasoning-effort 注入 */
    @Value("${spring.ai.openai.deepseek.reasoning-effort:high}")
    private String reasoningEffort;

    public DeepSeekChatModel(org.springframework.ai.chat.model.ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 构建 OpenAiChatOptions
     *
     * 设置 internalToolExecutionEnabled=false 以代理工具调用到客户端，
     * 并注入 ToolCallback。
     *
     * <p>思考模式配置:
     * <ul>
     *   <li>thinking=enabled → extra_body.thinking.type=enabled + reasoning_effort</li>
     *   <li>thinking=disabled → extra_body.thinking.type=disabled (DeepSeek 默认 thinking=enabled, 需显式关闭)</li>
     * </ul>
     * 思考模式不支持 temperature/top_p/presence_penalty/frequency_penalty, 这些参数已在 application.yml 移除.
     */
    private OpenAiChatOptions buildChatOptions(ToolCallback... tools) {
        OpenAiChatOptions chatOptions = new OpenAiChatOptions();
        chatOptions.setInternalToolExecutionEnabled(false);
        chatOptions.setMaxTokens(maxTokens);

        // 手动 new 的 OpenAiChatOptions 不会自动加载 YAML 配置, 需显式读取 @Value
        Map<String, Object> extraBody = new HashMap<>();
        Map<String, String> thinkingParam = new HashMap<>();
        if ("enabled".equalsIgnoreCase(this.thinking)) {
            // 思考模式开启: 显式发送 thinking + reasoning_effort
            thinkingParam.put("type", "enabled");
            extraBody.put("thinking", thinkingParam);
            chatOptions.setExtraBody(extraBody);
            chatOptions.setReasoningEffort(reasoningEffort);
        } else {
            // 思考模式关闭: DeepSeek 默认 thinking=enabled, 需显式禁用
            thinkingParam.put("type", "disabled");
            extraBody.put("thinking", thinkingParam);
            chatOptions.setExtraBody(extraBody);
        }

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
