package uno.zhuchen.agent.core.memory;

import java.util.List;

public interface MemoryVectorService {

    /** 向量通道是否可用; false 时调用方走纯结构化排序 */
    boolean available();

    void upsert(MemoryItemRecord item);

    void delete(String memoryItemId);

    /**
     * 语义召回: 按用户维度检索最相关的记忆条目。
     *
     * <p>失败必须返回空列表而不是抛异常, 由调用方回退结构化排序。
     */
    List<MemoryVectorHit> search(String tenantId, String userId, String projectId,
                                 String query, int topK, double minScore);
}
