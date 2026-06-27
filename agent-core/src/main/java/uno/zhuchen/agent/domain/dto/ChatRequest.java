package uno.zhuchen.agent.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求 DTO — 外部入参契约
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRequest {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4096, message = "消息内容不能超过 4096 字符")
    private String message;

    /**
     * 会话 ID（可选）
     *
     * 规则：
     * - null / 空字符串：视为新会话,由后端生成 UUID 并在首个 SSE 事件 (session_init) 中下发
     * - 非空：续聊,必须是合法 UUID 格式,否则后端返回 400
     *
     * 前端不需要持久化此字段:同一 tab 可放 URL hash (#conversationId),新 tab 不带则视为新会话。
     */
    private String conversationId;
}
