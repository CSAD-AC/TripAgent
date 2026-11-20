package uno.zhuchen.agent.tools.memory;

/**
 * 写入记忆向量库的一条记录。
 *
 * <p>id 必须与 agent-core 的 {@code memory_item.id} 一致, 保证向量结果能回查结构化记忆。
 */
public record MemoryVectorRecord(
        String id,
        String tenantId,
        String userId,
        String projectId,
        String memoryType,
        String memoryKey,
        String content
) {
}
