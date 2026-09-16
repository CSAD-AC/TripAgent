package uno.zhuchen.agent.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识文档上传请求 DTO — 转发层入参契约(与 mcp-server 同名字段对齐).
 *
 * <p>用途: agent-core 接收前端上传文档请求并原样转发 mcp-server;
 * 用强类型 DTO + {@code @Valid} 入参校验, 与 {@link ChatRequest} 风格一致.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeUploadRequest {

    /** 文档标题(必填) */
    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题不能超过 255 字符")
    private String title;

    /** 文档原文(必填) */
    @NotBlank(message = "内容不能为空")
    @Size(max = 200000, message = "内容不能超过 200000 字符")
    private String content;

    /** 目的地(可选) */
    private String destination;

    /** 主题(可选) */
    private String theme;

    /** 来源类型(manual/self_precip/...), 缺省 manual */
    private String sourceType;

    /** 溯源引用 */
    private String sourceRef;
}