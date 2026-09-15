package uno.zhuchen.agent.tools.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索命中条目 — 知识检索单条结果的强类型表示.
 *
 * <p>替代原先以 {@code Map<String,Object>} 手动拼字段的写法, 供返回给 LLM 的
 * JSON 序列化与前端展示复用一致结构. 字段名即序列化 key(下划线由 Jackson 依
 * 字段名直接输出).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalHit {

    /** 分块原文 */
    private String content;

    /** 所属文档标题 */
    private String docTitle;

    /** 目的地(metadata) */
    private String destination;

    /** 主题(metadata, 景点/美食/行程) */
    private String theme;

    /** 文档/分块主键(溯源用) */
    private String docId;

    /** 来源类型(seed/manual/self_precip/...) */
    private String source;

    /** 相似度分数 */
    private Double score;
}