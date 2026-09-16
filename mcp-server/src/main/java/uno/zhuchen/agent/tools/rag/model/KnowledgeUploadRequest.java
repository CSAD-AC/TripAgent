package uno.zhuchen.agent.tools.rag.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识文档上传请求 DTO — 外部入参契约.
 *
 * <p>替代原先 {@code @RequestBody Map<String,Object>} 的弱类型接收,
 * 支持 {@code @Valid} 参数校验(标题/内容必填), 与 agent-core 接口 DTO 风格一致.
 */
@Data
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

    /** 目的地(可选, 过滤检索用) */
    private String destination;

    /** 主题(可选, 景点/美食/行程等) */
    private String theme;

    /** 来源类型(manual/self_precip/graph_report/url/seed), 缺省 manual */
    private String sourceType;

    /** 溯源引用(traceId/conversationId/URL) */
    private String sourceRef;
}