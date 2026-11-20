package uno.zhuchen.agent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.common.exception.NotFoundException;
import uno.zhuchen.agent.domain.entity.MemoryItemEntity;
import uno.zhuchen.agent.domain.entity.MemoryRevisionEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryItemMapper;
import uno.zhuchen.agent.persistence.mapper.MemoryRevisionMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class MemoryGovernanceService {

    private final MemoryItemMapper itemMapper;
    private final MemoryItemService memoryItemService;
    private final MemoryRevisionMapper revisionMapper;
    private final MemoryVectorService vectorService;

    public MemoryGovernanceService(MemoryItemMapper itemMapper,
                                   MemoryItemService memoryItemService,
                                   MemoryRevisionMapper revisionMapper,
                                   MemoryVectorService vectorService) {
        this.itemMapper = itemMapper;
        this.memoryItemService = memoryItemService;
        this.revisionMapper = revisionMapper;
        this.vectorService = vectorService;
    }

    public void correct(String tenantId, String userId, String id, String newValue) {
        MemoryItemEntity entity = requireOwned(tenantId, userId, id);
        String previous = entity.getMemoryValue();
        try {
            itemMapper.update(null, new UpdateWrapper<MemoryItemEntity>()
                    .eq("id", id)
                    .set("memory_value", newValue)
                    .set("content", entity.getMemoryKey() + "=" + newValue)
                    .set("extractor_version", "user-correction")
                    .set("updated_at", LocalDateTime.now()));
            addRevision(tenantId, userId, id, "update", previous, newValue, "用户纠正");
            vectorService.delete(id);
        } catch (Exception e) {
            log.warn("[memory-governance] 纠正失败: id={}, error={}", id, e.getMessage());
        }
    }

    public String createVeto(String tenantId, String userId, String rule) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        MemoryItemRecord record = memoryItemService.upsert(
                tenantId,
                userId,
                null,
                MemoryItemType.VETO,
                "veto." + Integer.toHexString(rule.hashCode()),
                rule.trim(),
                "user_message",
                null,
                null,
                new java.math.BigDecimal("1.000"),
                new java.math.BigDecimal("1.000"),
                "user-veto");
        return record == null ? null : record.id();
    }

    public List<MemoryRevisionEntity> revisions(String tenantId, String userId, String memoryItemId) {
        requireOwned(tenantId, userId, memoryItemId);
        return revisionMapper.selectList(new QueryWrapper<MemoryRevisionEntity>()
                .eq("tenant_id", tenantId == null ? "default" : tenantId)
                .eq("user_id", userId)
                .eq("memory_item_id", memoryItemId)
                .orderByDesc("created_at"));
    }

    public void deleteWithAudit(String tenantId, String userId, String id) {
        MemoryItemEntity entity = requireOwned(tenantId, userId, id);
        try {
            itemMapper.update(null, new UpdateWrapper<MemoryItemEntity>()
                    .eq("id", id)
                    .set("memory_status", MemoryItemStatus.DELETED.name())
                    .set("embedding_status", MemoryVectorStatus.DELETED.name())
                    .set("deleted_at", LocalDateTime.now())
                    .set("updated_at", LocalDateTime.now()));
            addRevision(tenantId, userId, id, "delete", entity.getMemoryValue(), null, "用户删除");
            vectorService.delete(id);
        } catch (Exception e) {
            log.warn("[memory-governance] 删除失败: id={}, error={}", id, e.getMessage());
        }
    }

    private MemoryItemEntity requireOwned(String tenantId, String userId, String id) {
        if (userId == null || userId.isBlank() || id == null || id.isBlank()) {
            throw new NotFoundException("记忆不存在");
        }
        MemoryItemEntity entity = itemMapper.selectById(id);
        String normalizedTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        if (entity == null || !userId.equals(entity.getUserId())
                || !normalizedTenant.equals(entity.getTenantId())
                || !MemoryItemStatus.ACTIVE.name().equals(entity.getMemoryStatus())) {
            throw new NotFoundException("记忆不存在");
        }
        return entity;
    }

    private void addRevision(String tenantId, String userId, String memoryItemId,
                             String operation, String previousValue, String newValue, String reason) {
        MemoryRevisionEntity revision = new MemoryRevisionEntity();
        revision.setId(UUID.randomUUID().toString());
        revision.setTenantId(tenantId == null ? "default" : tenantId);
        revision.setUserId(userId);
        revision.setMemoryItemId(memoryItemId);
        revision.setOperation(operation);
        revision.setPreviousValue(previousValue);
        revision.setNewValue(newValue);
        revision.setActor(userId);
        revision.setReason(reason);
        revision.setCreatedAt(LocalDateTime.now());
        revisionMapper.insert(revision);
    }
}
