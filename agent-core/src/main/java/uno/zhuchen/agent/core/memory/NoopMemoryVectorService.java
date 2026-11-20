package uno.zhuchen.agent.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.memory.hybrid.vector-enabled", havingValue = "false", matchIfMissing = true)
public class NoopMemoryVectorService implements MemoryVectorService {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public void upsert(MemoryItemRecord item) {
        log.debug("[memory-vector] 向量写入已禁用, id={}", item == null ? null : item.id());
    }

    @Override
    public void delete(String memoryItemId) {
        log.debug("[memory-vector] 向量删除已禁用, id={}", memoryItemId);
    }

    @Override
    public List<MemoryVectorHit> search(String tenantId, String userId, String projectId,
                                        String query, int topK, double minScore) {
        return List.of();
    }
}
