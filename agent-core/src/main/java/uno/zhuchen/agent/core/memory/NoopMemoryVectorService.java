package uno.zhuchen.agent.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.memory.hybrid.enabled", havingValue = "false", matchIfMissing = true)
public class NoopMemoryVectorService implements MemoryVectorService {

    @Override
    public void upsert(MemoryItemRecord item) {
        log.debug("[memory-vector] 向量写入已禁用, id={}", item == null ? null : item.id());
    }

    @Override
    public void delete(String memoryItemId) {
        log.debug("[memory-vector] 向量删除已禁用, id={}", memoryItemId);
    }

    @Override
    public void rebuild(String tenantId, String userId) {
        log.debug("[memory-vector] 重建已禁用, tenantId={}, userId={}", tenantId, userId);
    }
}
