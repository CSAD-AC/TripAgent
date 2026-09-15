package uno.zhuchen.agent.tools.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识文档视图对象 — 文档列表/详情对外返回的结构.
 *
 * <p>替代原先按 {@code Map<String,Object>} 手动拼字段的 {@code docToMap},
 * createdAt 以字符串暴露, 规避 webflux Jackson 未注册 jsr310 对
 * {@code LocalDateTime} 序列化的报错.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocVO {

    /** 文档主键 */
    private String id;

    /** 文档标题 */
    private String title;

    /** 来源类型(manual/self_precip/graph_report/seed/url) */
    private String sourceType;

    /** 溯源引用 */
    private String sourceRef;

    /** 索引状态(pending/processing/indexed/failed) */
    private String status;

    /** 分块数量 */
    private Integer chunkCount;

    /** 入库失败原因 */
    private String errorMsg;

    /** 创建时间(字符串形式, 如 2026-09-15T21:18:20.607) */
    private String createdAt;
}