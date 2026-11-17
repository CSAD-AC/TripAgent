package uno.zhuchen.agent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.domain.entity.MemoryIngestEventEntity;
import uno.zhuchen.agent.domain.entity.MemoryItemEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryIngestEventMapper;
import uno.zhuchen.agent.persistence.mapper.MemoryItemMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MemoryAdminService {

    private final MemoryItemMapper itemMapper;
    private final MemoryIngestEventMapper eventMapper;
    private final MemoryExtractionWorker extractionWorker;
    private final MemoryVectorService vectorService;

    public MemoryAdminService(MemoryItemMapper itemMapper,
                              MemoryIngestEventMapper eventMapper,
                              MemoryExtractionWorker extractionWorker,
                              MemoryVectorService vectorService) {
        this.itemMapper = itemMapper;
        this.eventMapper = eventMapper;
        this.extractionWorker = extractionWorker;
        this.vectorService = vectorService;
    }

    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeItems", countItems(MemoryItemStatus.ACTIVE.name()));
        result.put("supersededItems", countItems(MemoryItemStatus.SUPERSEDED.name()));
        result.put("deletedItems", countItems(MemoryItemStatus.DELETED.name()));
        result.put("pendingEvents", countEvents("pending"));
        result.put("failedEvents", countEvents("failed"));
        result.put("pendingVectors", countByVectorStatus(MemoryVectorStatus.PENDING.name()));
        result.put("failedVectors", countByVectorStatus(MemoryVectorStatus.FAILED.name()));
        return result;
    }

    public int processPendingEvents(int limit) {
        return extractionWorker.processPendingBatch(limit);
    }

    public void rebuildUser(String tenantId, String userId) {
        vectorService.rebuild(tenantId, userId);
    }

    public int expireStaleItems(int retentionDays) {
        int safeDays = Math.max(1, retentionDays);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(safeDays);
        return itemMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MemoryItemEntity>()
                .eq("memory_status", MemoryItemStatus.ACTIVE.name())
                .lt("updated_at", cutoff)
                .set("memory_status", MemoryItemStatus.DELETED.name())
                .set("embedding_status", MemoryVectorStatus.DELETED.name())
                .set("deleted_at", LocalDateTime.now()));
    }

    private long countItems(String status) {
        return itemMapper.selectCount(new QueryWrapper<MemoryItemEntity>()
                .eq("memory_status", status));
    }

    private long countEvents(String status) {
        return eventMapper.selectCount(new QueryWrapper<MemoryIngestEventEntity>()
                .eq("event_status", status));
    }

    private long countByVectorStatus(String status) {
        return itemMapper.selectCount(new QueryWrapper<MemoryItemEntity>()
                .eq("embedding_status", status));
    }
}
