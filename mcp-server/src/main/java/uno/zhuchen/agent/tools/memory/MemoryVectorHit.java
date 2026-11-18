package uno.zhuchen.agent.tools.memory;

/** 记忆向量检索命中一条。 */
public record MemoryVectorHit(
        String id,
        double score,
        String content,
        String memoryType,
        String memoryKey
) {
}
