package uno.zhuchen.agent.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式响应块 — SSE 模式下逐块推送给前端
 *
 * 事件类型：
 *
 * - thinking — LLM 返回的一个文本片段，前端追加到对话气泡
 * - tool_call — LLM 发起了工具调用请求
 * - tool_result — 工具执行完成，返回结果摘要
 * - timestamp — 时间戳
 * - final — 整个 ReAct 循环完成，携带完整结果和元数据
 * - error — 执行过程中发生异常，前端展示错误状态
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StreamChunk {

    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_FINAL = "final";
    public static final String TYPE_ERROR = "error";

    /** 全流程流式新增事件类型 */
    public static final String TYPE_THINKING_TOKEN = "thinking_token";
    public static final String TYPE_TOOL_CALL_START = "tool_call_start";
    public static final String TYPE_TOOL_ERROR = "tool_error";
    public static final String TYPE_ITERATION_SEPARATOR = "iteration_separator";

    /** 事件类型：thinking / tool_call / tool_result / final / error */
    private String type;

    /** 文本内容或错误消息 */
    private String content;

    /** 会话 ID */
    private String conversationId;

    /** 工具名称（tool_call / tool_result 事件有效） */
    private String toolName;

    /** 工具参数 JSON（tool_call 事件有效） */
    private String toolArguments;

    /** 工具结果摘要（tool_result 事件有效） */
    private String toolResult;

    /** 迭代信息（thinking_token 事件有效） */
    private String iterationInfo;

    /** 耗时（ms），仅 final/error 事件有效 */
    private long durationMs;

    public static StreamChunk thinking(String content, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_THINKING)
                .content(content)
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk toolCall(String toolName, String arguments, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_TOOL_CALL)
                .toolName(toolName)
                .toolArguments(arguments)
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk toolResult(String toolName, String resultSummary, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_TOOL_RESULT)
                .toolName(toolName)
                .toolResult(resultSummary)
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk final_(String conversationId, String answer, long durationMs) {
        return StreamChunk.builder()
                .type(TYPE_FINAL)
                .content(answer)
                .conversationId(conversationId)
                .durationMs(durationMs)
                .build();
    }

    // ========== 全流程流式新增工厂方法 ==========

    /**
     * LLM 思考过程的一个 token（实时推送）
     */
    public static StreamChunk thinkingToken(String token, String conversationId, String iterationInfo) {
        return StreamChunk.builder()
                .type(TYPE_THINKING_TOKEN)
                .content(token)
                .conversationId(conversationId)
                .iterationInfo(iterationInfo)
                .build();
    }

    /**
     * 工具调用开始（前置通知）
     */
    public static StreamChunk toolCallStart(String conversationId, String msg) {
        return StreamChunk.builder()
                .type(TYPE_TOOL_CALL_START)
                .content(msg)
                .conversationId(conversationId)
                .build();
    }

    /**
     * 工具执行出错
     */
    public static StreamChunk toolError(String toolName, String error, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_TOOL_ERROR)
                .toolName(toolName)
                .toolResult(error)
                .conversationId(conversationId)
                .build();
    }

    /**
     * 迭代之间的分隔事件
     */
    public static StreamChunk iterationSeparator(String conversationId, String content) {
        return StreamChunk.builder()
                .type(TYPE_ITERATION_SEPARATOR)
                .content(content)
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk error(String conversationId, String errorMessage, long durationMs) {
        return StreamChunk.builder()
                .type(TYPE_ERROR)
                .content(errorMessage)
                .conversationId(conversationId)
                .durationMs(durationMs)
                .build();
    }

    public static StreamChunk timestamp(String content, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_THINKING)
                .content(content)
                .conversationId(conversationId)
                .build();
    }
}
