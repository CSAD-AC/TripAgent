package uno.zhuchen.agent.tools.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识分块视图对象 — 供前端阅读某篇文档的分块内容.
 *
 * <p>收敛 {@code KnowledgeDocController /chunks} 接口出参, 序列化 key: id / seq / content / destination / theme.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunkVO {

    /** 分块主键 */
    private String id;

    /** 文档内分块序号 */
    private Integer seq;

    /** 分块原文 */
    private String content;

    /** 目的地(metadata) */
    private String destination;

    /** 主题(metadata) */
    private String theme;
}