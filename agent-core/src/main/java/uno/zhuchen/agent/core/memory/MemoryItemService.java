package uno.zhuchen.agent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.common.exception.NotFoundException;
import uno.zhuchen.agent.domain.entity.MemoryItemEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryItemMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class MemoryItemService {

    private static final String DEFAULT_TENANT = "default";

    private final MemoryItemMapper mapper;

    public MemoryItemService(MemoryItemMapper mapper) {
        this.mapper = mapper;
    }

    public MemoryItemRecord upsert(String tenantId,
                                   String userId,
                                   String projectId,
                                   MemoryItemType type,
                                   String key,
                                   String value,
                                   String sourceType,
                                   String sourceConversationId,
                                   String sourceMessageId,
                                   BigDecimal confidence,
                                   BigDecimal importance,
                                   String extractorVersion) {
        if (userId == null || userId.isBlank() || key == null || key.isBlank()
                || value == null || value.isBlank() || type == null) {
            return null;
        }
        try {
            String normalizedTenant = normalizeTenant(tenantId);
            String normalizedProject = normalizeProject(projectId);
            LocalDateTime now = LocalDateTime.now();
            String content = buildContent(key, value);
            MemoryVectorStatus vectorStatus = type == MemoryItemType.EPISODIC
                    ? MemoryVectorStatus.PENDING
                    : MemoryVectorStatus.NOT_REQUIRED;

            MemoryItemEntity existing = null;
            if (type != MemoryItemType.EPISODIC) {
                existing = mapper.selectOne(new QueryWrapper<MemoryItemEntity>()
                        .eq("tenant_id", normalizedTenant)
                        .eq("user_id", userId)
                        .eq("project_id", normalizedProject)
                        .eq("memory_type", type.name())
                        .eq("memory_key", key)
                        .eq("memory_status", MemoryItemStatus.ACTIVE.name())
                        .last("LIMIT 1"));
            }

            if (existing != null) {
                mapper.update(null, new UpdateWrapper<MemoryItemEntity>()
                        .eq("id", existing.getId())
                        .set("memory_value", value)
                        .set("content", content)
                        .set("source_type", sourceType)
                        .set("source_conversation_id", sourceConversationId)
                        .set("source_message_id", sourceMessageId)
                        .set("confidence", confidence)
                        .set("importance", importance)
                        .set("extractor_version", extractorVersion)
                        .set("embedding_status", vectorStatus.name())
                        .set("updated_at", now));
                existing.setMemoryValue(value);
                existing.setContent(content);
                existing.setConfidence(confidence);
                existing.setImportance(importance);
                existing.setExtractorVersion(extractorVersion);
                existing.setEmbeddingStatus(vectorStatus.name());
                existing.setUpdatedAt(now);
                return toRecord(existing);
            }

            MemoryItemEntity entity = new MemoryItemEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setTenantId(normalizedTenant);
            entity.setUserId(userId);
            entity.setProjectId(normalizedProject);
            entity.setMemoryType(type.name());
            entity.setMemoryKey(key);
            entity.setMemoryValue(value);
            entity.setContent(content);
            entity.setSourceType(sourceType == null ? "user_message" : sourceType);
            entity.setSourceConversationId(sourceConversationId);
            entity.setSourceMessageId(sourceMessageId);
            entity.setConfidence(confidence == null ? new BigDecimal("0.800") : confidence);
            entity.setImportance(importance == null ? new BigDecimal("0.500") : importance);
            entity.setMemoryStatus(MemoryItemStatus.ACTIVE.name());
            entity.setEmbeddingStatus(vectorStatus.name());
            entity.setExtractorVersion(extractorVersion == null ? "rule-v1" : extractorVersion);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);
            return toRecord(entity);
        } catch (Exception e) {
            log.warn("[memory-item] upsert 失败: userId={}, key={}, error={}", userId, key, e.getMessage());
            return null;
        }
    }

    public void supersede(String tenantId, String userId, String id, String replacementId) {
        if (userId == null || userId.isBlank() || id == null || id.isBlank()) {
            return;
        }
        try {
            mapper.update(null, new UpdateWrapper<MemoryItemEntity>()
                    .eq("id", id)
                    .eq("tenant_id", normalizeTenant(tenantId))
                    .eq("user_id", userId)
                    .eq("memory_status", MemoryItemStatus.ACTIVE.name())
                    .set("memory_status", MemoryItemStatus.SUPERSEDED.name())
                    .set("superseded_by", replacementId)
                    .set("updated_at", LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[memory-item] supersede 失败: userId={}, id={}, error={}", userId, id, e.getMessage());
        }
    }

    public List<MemoryItemRecord> list(String tenantId, String userId, String projectId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            QueryWrapper<MemoryItemEntity> query = new QueryWrapper<MemoryItemEntity>()
                    .eq("tenant_id", normalizeTenant(tenantId))
                    .eq("user_id", userId)
                    .eq("memory_status", MemoryItemStatus.ACTIVE.name())
                    .orderByDesc("updated_at");
            if (projectId != null && !projectId.isBlank()) {
                query.eq("project_id", projectId);
            }
            return mapper.selectList(query).stream().map(MemoryItemService::toRecord).toList();
        } catch (Exception e) {
            log.warn("[memory-item] 列表查询失败: userId={}, error={}", userId, e.getMessage());
            return List.of();
        }
    }

    public MemoryItemRecord findByIdForUser(String tenantId, String userId, String id) {
        if (userId == null || userId.isBlank() || id == null || id.isBlank()) {
            throw new NotFoundException("记忆不存在");
        }
        MemoryItemEntity entity;
        try {
            entity = mapper.selectById(id);
        } catch (Exception e) {
            log.warn("[memory-item] 按 ID 查询失败: userId={}, id={}, error={}", userId, id, e.getMessage());
            throw new NotFoundException("记忆不存在");
        }
        if (entity == null
                || !userId.equals(entity.getUserId())
                || !normalizeTenant(tenantId).equals(entity.getTenantId())
                || !MemoryItemStatus.ACTIVE.name().equals(entity.getMemoryStatus())) {
            throw new NotFoundException("记忆不存在");
        }
        return toRecord(entity);
    }

    public void delete(String tenantId, String userId, String id) {
        findByIdForUser(tenantId, userId, id);
        try {
            mapper.update(null, new UpdateWrapper<MemoryItemEntity>()
                    .eq("id", id)
                    .eq("tenant_id", normalizeTenant(tenantId))
                    .eq("user_id", userId)
                    .set("memory_status", MemoryItemStatus.DELETED.name())
                    .set("embedding_status", MemoryVectorStatus.DELETED.name())
                    .set("deleted_at", LocalDateTime.now())
                    .set("updated_at", LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[memory-item] 删除失败: userId={}, id={}, error={}", userId, id, e.getMessage());
        }
    }

    public void deleteAll(String tenantId, String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            mapper.update(null, new UpdateWrapper<MemoryItemEntity>()
                    .eq("tenant_id", normalizeTenant(tenantId))
                    .eq("user_id", userId)
                    .eq("memory_status", MemoryItemStatus.ACTIVE.name())
                    .set("memory_status", MemoryItemStatus.DELETED.name())
                    .set("embedding_status", MemoryVectorStatus.DELETED.name())
                    .set("deleted_at", LocalDateTime.now())
                    .set("updated_at", LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[memory-item] 批量删除失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
    }

    private static String normalizeProject(String projectId) {
        return projectId == null ? "" : projectId;
    }

    private static String buildContent(String key, String value) {
        return key + "=" + value;
    }

    private static MemoryItemRecord toRecord(MemoryItemEntity entity) {
        return new MemoryItemRecord(
                entity.getId(),
                entity.getTenantId(),
                entity.getUserId(),
                entity.getProjectId(),
                parseEnum(MemoryItemType.class, entity.getMemoryType(), MemoryItemType.PREFERENCE),
                entity.getMemoryKey(),
                entity.getMemoryValue(),
                entity.getContent(),
                entity.getAttributesJson(),
                entity.getSourceType(),
                entity.getSourceConversationId(),
                entity.getSourceMessageId(),
                entity.getConfidence(),
                entity.getImportance(),
                parseEnum(MemoryItemStatus.class, entity.getMemoryStatus(), MemoryItemStatus.ACTIVE),
                entity.getSupersededBy(),
                entity.getExpiresAt(),
                parseEnum(MemoryVectorStatus.class, entity.getEmbeddingStatus(), MemoryVectorStatus.NOT_REQUIRED),
                entity.getEmbeddingModel(),
                entity.getEmbeddingVersion(),
                entity.getExtractorVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
