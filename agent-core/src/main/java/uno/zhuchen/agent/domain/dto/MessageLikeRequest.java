package uno.zhuchen.agent.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息点赞沉淀请求 DTO — 前端点赞某条 assistant 回答时提交入参.
 *
 * <p>转发层用强类型 DTO + {@code @Valid} 校验, 与项目接口风格一致.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageLikeRequest {

    /** 会话 ID(可选, 溯源) */
    private String conversationId;

    /** 被点赞的 assistant 回答全文(必填, 将沉淀为知识库分块) */
    @NotBlank(message = "点赞内容不能为空")
    // 两档语义: 此处 max 是抗超大包的入参硬门槛(拦截超长回包),
    // 业务上 MessageController 还会截断到 MAX_PRECIP_CONTENT=20000 入库, 见点赞接口注释.
    @Size(max = 200000, message = "点赞内容过长")
    private String content;

    /** 目的地(可选, 检索过滤用) */
    private String destination;
}