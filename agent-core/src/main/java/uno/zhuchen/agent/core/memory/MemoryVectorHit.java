package uno.zhuchen.agent.core.memory;

/**
 * 记忆向量召回命中。
 *
 * @param id   命中的 {@code memory_item.id}
 * @param score 相似度(0~1, 越高越相关)
 */
public record MemoryVectorHit(String id, double score, String content) {
}
