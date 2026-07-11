package uno.zhuchen.agent.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.core.clarify.ClarificationBroker;
import uno.zhuchen.agent.domain.dto.StreamChunk;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * 反问工具
 *
 * 当 LLM 缺少关键信息无法继续时调用，弹出结构化问题让用户选/填。
 *
 * 与 MCP 工具不同，本工具是内置工具（不走 MCP）——它需要长连接阻塞直到用户回答。
 *
 * 工作流：
 * 1. LLM 决定调用 askUser(question, options, allowCustom)
 * 2. Spring AI 自动包装成 ToolCallback，注册到 ToolRegistry
 * 3. ReactAgent 通过 ToolCallback.call() 调用本方法
 * 4. 本方法通过 ClarificationBroker 阻塞等用户回答
 * 5. 用户通过 POST /api/chat/answer 提交答案
 * 6. 本方法返回答案字符串,继续 ReAct 循环
 *
 * <p>上下文传递: conversationId 和 traceId 通过 ThreadLocal 传递（由调用方在 invoke 工具前 set）.
 * 用 ThreadLocal 而非实例字段 —— 同一 AskUserTool Bean 在多会话并发时不会串号.
 * try-finally 保证清理, 防 Tomcat / worker 线程复用串号.
 *
 * <p>为什么这里仍用 ThreadLocal 而非 Reactor Context:
 * 1. {@code @Tool} 注解的方法是 Spring AI 框架同步调用入口, 不在 reactive 链上, 无法用 ContextView
 * 2. 仅本工具使用这两个 ThreadLocal, 不蔓延到其他模块, 局部作用域
 * 3. 配合 try-finally 调用模式, 不会跨线程泄漏
 *
 * <p>并发互斥 (P1 修复):
 * <ul>
 *   <li>同一 conversationId 同时只能有一个 askUser 在阻塞等用户回答</li>
 *   <li>第二个并发的 askUser 调用会被拒绝, 抛 {@link AskUserBusyException}</li>
 *   <li>调用方 (BaseAgent.callLLMWithTools) 的 catch 块会捕获异常并转成 ToolResponse 错误给 LLM</li>
 *   <li>触发场景: Graph parallel_group 同时触发 route + itinerary 两个 worker,
 *       它们各自 LLM 独立决定调 askUser 反问用户, 第二个会拒绝</li>
 * </ul>
 */
@Component
public class AskUserTool {

    private static final Logger log = LoggerFactory.getLogger(AskUserTool.class);

    /** 当前 conversationId，由调用方在 invoke 工具前 set */
    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    /** 当前 traceId，由调用方在 invoke 工具前 set（同步入口可传空串） */
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    /**
     * 当前正在阻塞等用户回答的 conversationId → traceId 映射.
     * 用于并发互斥: 同一 conversationId 同时只能有一个 askUser 阻塞.
     */
    private static final ConcurrentHashMap<String, String> ACTIVE_CONVERSATIONS = new ConcurrentHashMap<>();

    private final ClarificationBroker broker;
    private final ObjectMapper objectMapper;

    public AskUserTool(ClarificationBroker broker, ObjectMapper objectMapper) {
        this.broker = broker;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用入口: 在 invoke 工具前同时设置 conversationId 和 traceId
     *
     * <p>并发检查: 同一 conversationId 已有 askUser 在阻塞时, 抛 {@link AskUserBusyException},
     * 阻止第二个反问覆盖第一个的 ThreadLocal. 调用方应 catch 异常并转成 ToolResponse 错误给 LLM.
     */
    public static void setContext(String conversationId, String traceId) {
        String safeTrace = traceId == null ? "" : traceId;
        String existing = ACTIVE_CONVERSATIONS.putIfAbsent(conversationId, safeTrace);
        if (existing != null) {
            log.warn("[traceId={}] AskUserTool 并发拒绝 setContext: conversationId={} 已有 askUser 阻塞 (traceId={})",
                    safeTrace, conversationId, existing);
            throw new AskUserBusyException(
                    "当前会话 (conversationId=" + conversationId + ") 已有反问在等待用户回答 "
                            + "(活跃 traceId=" + existing + "), 请基于已有信息/合理假设继续回答, 不要重复反问");
        }
        CONVERSATION_ID.set(conversationId);
        TRACE_ID.set(safeTrace);
    }

    /**
     * 调用入口: 调用后清理 ThreadLocal, 防 Tomcat / worker 线程复用串号
     * 同时从 {@link #ACTIVE_CONVERSATIONS} 释放会话登记
     */
    public static void clearContext() {
        String conversationId = CONVERSATION_ID.get();
        if (conversationId != null) {
            ACTIVE_CONVERSATIONS.remove(conversationId);
        }
        CONVERSATION_ID.remove();
        TRACE_ID.remove();
    }

    @Tool(description = "向用户反问以补充关键信息。仅当缺少必要信息无法继续时才调用，"
            + "问题要简短清晰,2-4 个预设选项,allowCustom 必须传 true 以便用户自由补充。"
            + "不要为可有可无的信息打断用户,只在关键字段缺失导致无法继续时使用。")
    public String askUser(
            @ToolParam(required = true, description = "向用户提出的问题,简短清晰,例如「预算范围是?」") String question,
            @ToolParam(required = true, description = "预设选项列表,2-4 个,每个含 label(显示文本)和 value(传给 LLM 的值)") List<Option> options,
            @ToolParam(required = false, description = "是否允许用户自由输入,默认 true") Boolean allowCustom) {

        String conversationId = CONVERSATION_ID.get();
        String traceId = TRACE_ID.get() == null ? "" : TRACE_ID.get();
        if (conversationId == null) {
            log.error("[traceId={}] AskUserTool 被调用时 conversationId 为空 - 这通常是 ReactAgent 未正确 setContext 导致的",
                    traceId);
            return "反问失败:会话上下文丢失,请重试";
        }

        boolean allowInput = allowCustom == null || allowCustom;
        String questionId = UUID.randomUUID().toString();
        String optionsJson = serializeOptions(options);

        log.info("[traceId={}] 触发反问: conversationId={}, questionId={}, question={}",
                traceId, conversationId, questionId, question);

        StreamChunk event = StreamChunk.clarificationRequest(
                conversationId, questionId, question, optionsJson, allowInput);

        try {
            String userAnswer = broker.awaitAnswer(conversationId, questionId, event);
            log.info("[traceId={}] 反问得到回答: questionId={}, answer={}",
                    traceId, questionId, userAnswer);
            return "用户回答: " + userAnswer;
        } catch (TimeoutException e) {
            log.warn("[traceId={}] 反问超时: questionId={}, 让 Agent 走默认假设继续",
                    traceId, questionId);
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

    /**
     * 同一 conversationId 已有 askUser 在阻塞时抛出.
     * 转成 ToolResponse 错误字符串告诉 LLM "不要重复反问".
     */
    public static class AskUserBusyException extends RuntimeException {
        public AskUserBusyException(String message) {
            super(message);
        }
    }
}
