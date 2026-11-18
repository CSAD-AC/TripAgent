package uno.zhuchen.agent.tools.memory;

/**
 * 记忆向量检索请求。
 *
 * <p>tenantId / userId 由服务端强制拼进 Milvus 过滤表达式, 不接受调用方传入原始表达式,
 * 避免跨用户召回。
 */
public record MemoryVectorSearchRequest(
        String tenantId,
        String userId,
        String projectId,
        String query,
        Integer topK,
        Double minScore
) {
}
