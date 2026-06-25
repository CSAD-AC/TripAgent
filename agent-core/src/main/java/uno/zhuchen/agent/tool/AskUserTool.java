package uno.zhuchen.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.clarify.ClarificationBroker;
import uno.zhuchen.agent.domain.dto.StreamChunk;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 反问工具
 *
 * <p>当 LLM 缺少关键信息无法继续时调用，弹出结构化问题让用户选/填。
 * <p>与 MCP 工具不同，本工具是内置工具（不走 MCP）——它需要**长连接阻塞**直到用户回答。
 * <p>工作流：
 * <ol>
 *   <li>LLM 决定调用 askUser(question, options, allowCustom)</li>
 *   <li>Spring AI 自动包装成 ToolCallback，注册到 ToolRegistry</li>
 *   <li>ReactAgent 通过 ToolCallback.call() 调用本方法</li>
 *   <li>本方法通过 ClarificationBroker 阻塞等用户回答</li>
 *   <li>用户通过 POST /api/chat/answer 提交答案</li>
 *   <li>本方法返回答案字符串,继续 ReAct 循环</li>
 * </ol>
 *
 * <p>conversationId 通过 ThreadLocal 传递（由 ReactAgent 在调用工具前 set）
 */
@Component
public class AskUserTool {

    private static final Logger log = LoggerFactory.getLogger(AskUserTool.class);

    /**
     * 当前 conversationId，由 ReactAgent 在调用工具前 set。
     * 用 ThreadLocal 而非实例字段——同一 AskUserTool Bean 在多会话并发时不会串号。
     */
    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    private final ClarificationBroker broker;
    private final ObjectMapper objectMapper;

    public AskUserTool(ClarificationBroker broker, ObjectMapper objectMapper) {
        this.broker = broker;
        this.objectMapper = objectMapper;
    }

    /**
     * ReactAgent 调用入口：在 invoke 工具前 set conversationId
     */
    public static void setConversationId(String conversationId) {
        CONVERSATION_ID.set(conversationId);
    }

    /**
     * ReactAgent 调用入口：调用后清理 ThreadLocal 避免线程复用导致串号
     */
    public static void clearConversationId() {
        CONVERSATION_ID.remove();
    }

    @Tool(description = "向用户反问以补充关键信息。仅当缺少必要信息无法继续时才调用，"
            + "问题要简短清晰,2-4 个预设选项,allowCustom 必须传 true 以便用户自由补充。"
            + "不要为可有可无的信息打断用户,只在关键字段缺失导致无法继续时使用。")
    public String askUser(
            @ToolParam(required = true, description = "向用户提出的问题,简短清晰,例如「预算范围是?」") String question,
            @ToolParam(required = true, description = "预设选项列表,2-4 个,每个含 label(显示文本)和 value(传给 LLM 的值)") List<Option> options,
            @ToolParam(required = false, description = "是否允许用户自由输入,默认 true") Boolean allowCustom) {

        String conversationId = CONVERSATION_ID.get();
        if (conversationId == null) {
            log.error("AskUserTool 被调用时 conversationId 为空 - 这通常是 ReactAgent 未正确 setContext 导致的");
            return "反问失败:会话上下文丢失,请重试";
        }

        boolean allowInput = allowCustom == null || allowCustom;
        String questionId = UUID.randomUUID().toString();
        String optionsJson = serializeOptions(options);

        log.info("触发反问: conversationId={}, questionId={}, question={}, options={}",
                conversationId, questionId, question, optionsJson);

        StreamChunk event = StreamChunk.clarificationRequest(
                conversationId, questionId, question, optionsJson, allowInput);

        try {
            String userAnswer = broker.awaitAnswer(conversationId, questionId, event);
            log.info("反问得到回答: questionId={}, answer={}", questionId, userAnswer);
            return "用户回答: " + userAnswer;
        } catch (TimeoutException e) {
            log.warn("反问超时: questionId={}, 让 Agent 走默认假设继续", questionId);
            return "用户未在限定时间内回答,请基于已有信息/合理假设继续回答";
        }
    }

    private String serializeOptions(List<Option> options) {
        if (options == null || options.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            log.warn("Options 序列化失败,使用空数组: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 预设选项
     */
    public record Option(String label, String value) {

        /** LLM 可能直接传 Map,做一次兜底解析 */
        public static Option fromMap(Map<String, String> map) {
            if (map == null) {
                return null;
            }
            return new Option(map.get("label"), map.get("value"));
        }
    }
}
