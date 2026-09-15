package uno.zhuchen.agent.tools.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识检索响应体 — 混合检索返回给 LLM 的最外层结构.
 *
 * <p>收敛 {@code Map<String,Object>} 手动拼字段的写法, 字段即序列化 key:
 * {@code results / degraded / tookMs / hint}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResponse {

    /** 命中条目(RetrievalHit) */
    private List<RetrievalHit> results;

    /** 降级标记: none / milvus / keyword / rerank / filter_relaxed / rerank_empty */
    private String degraded;

    /** 本次检索耗时(ms) */
    private Long tookMs;

    /** 无结果时的引导提示(如建议转 webSearch); 正常时可为 null */
    private String hint;
}