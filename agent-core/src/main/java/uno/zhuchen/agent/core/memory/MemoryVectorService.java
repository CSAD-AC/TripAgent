package uno.zhuchen.agent.core.memory;

public interface MemoryVectorService {

    void upsert(MemoryItemRecord item);

    void delete(String memoryItemId);

    void rebuild(String tenantId, String userId);
}
