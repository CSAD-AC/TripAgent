package uno.zhuchen.agent.tools.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 知识库统计响应 — 文档/分块数量及状态分布.
 *
 * <p>字段即序列化 key: {@code docTotal / chunkTotal / docByStatus / docBySourceType}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeStats {

    /** 文档总数(不含已删除) */
    private long docTotal;

    /** 分块总数 */
    private long chunkTotal;

    /** 文档按索引状态分组计数(pending/indexed/failed/deleted...) */
    private Map<String, Long> docByStatus;

    /** 文档按来源类型分组计数(manual/self_precip/seed/...) */
    private Map<String, Long> docBySourceType;
}