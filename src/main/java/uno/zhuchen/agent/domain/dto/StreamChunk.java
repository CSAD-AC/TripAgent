package uno.zhuchen.agent.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式响应块 — SSE 模式下逐块推送给前端
 *
 * 三种事件类型：
 *
 * - thinking — LLM 返回的一个文本片段，前端追加到对话气泡
 * - final — 整个 ReAct 循环完成，携带完整结果和元数据
 * - error — 执行过程中发生异常，前端展示错误状态
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StreamChunk {

    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_FINAL = "final";
    public static final String TYPE_ERROR = "error";

    /** 事件类型：thinking / final / error */
    private String type;

    /** 文本内容或错误消息 */
    private String content;

    /** 会话 ID */
    private String conversationId;

    /** 耗时（ms），仅 final/error 事件有效 */
    private long durationMs;

    public static StreamChunk thinking(String content, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_THINKING)
                .content(content)
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

    public static StreamChunk error(String conversationId, String errorMessage, long durationMs) {
        return StreamChunk.builder()
                .type(TYPE_ERROR)
                .content(errorMessage)
                .conversationId(conversationId)
                .durationMs(durationMs)
                .build();
    }
}
