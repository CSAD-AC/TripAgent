package uno.zhuchen.agent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.domain.entity.MemoryIngestEventEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryIngestEventMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class MemoryIngestService {

    private static final String DEFAULT_TENANT = "default";
    private static final int MAX_PAYLOAD_BYTES = 8192;

    private final MemoryIngestEventMapper mapper;

    public MemoryIngestService(MemoryIngestEventMapper mapper) {
        this.mapper = mapper;
    }

    public String enqueue(String tenantId,
                          String userId,
                          String conversationId,
                          String eventType,
                          String payloadJson) {
        if (userId == null || userId.isBlank() || payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        String payload = truncatePayload(payloadJson);
        MemoryIngestEventEntity event = new MemoryIngestEventEntity();
        event.setId(UUID.randomUUID().toString());
        event.setTenantId(normalizeTenant(tenantId));
        event.setUserId(userId);
        event.setConversationId(conversationId);
        event.setEventType(eventType == null ? "message" : eventType);
        event.setPayloadJson(payload);
        event.setEventStatus("pending");
        event.setAttemptCount(0);
        event.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(event);
            return event.getId();
        } catch (Exception e) {
            log.warn("[memory-ingest] 入队失败: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }

    public List<MemoryIngestEventEntity> claimBatch(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        try {
            List<MemoryIngestEventEntity> events = mapper.selectList(new QueryWrapper<MemoryIngestEventEntity>()
                    .eq("event_status", "pending")
                    .orderByAsc("created_at")
                    .last("LIMIT " + safeLimit));
            if (events.isEmpty()) {
                return List.of();
            }
            mapper.update(null, new UpdateWrapper<MemoryIngestEventEntity>()
                    .in("id", events.stream().map(MemoryIngestEventEntity::getId).toList())
                    .eq("event_status", "pending")
                    .set("event_status", "processing"));
            return events;
        } catch (Exception e) {
            log.warn("[memory-ingest] 领取事件失败: error={}", e.getMessage());
            return List.of();
        }
    }

    public void markDone(String id) {
        updateStatus(id, "done", null, true);
    }

    public void markFailed(String id, String error) {
        updateStatus(id, "failed", error, true);
    }

    public void markSkipped(String id) {
        updateStatus(id, "skipped", null, true);
    }

    private void updateStatus(String id, String status, String error, boolean incrementAttempt) {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            UpdateWrapper<MemoryIngestEventEntity> update = new UpdateWrapper<MemoryIngestEventEntity>()
                    .eq("id", id)
                    .set("event_status", status)
                    .set("processed_at", LocalDateTime.now());
            if (error != null) {
                update.set("last_error", truncate(error, 500));
            }
            if (incrementAttempt) {
                update.setSql("attempt_count = attempt_count + 1");
            }
            mapper.update(null, update);
        } catch (Exception e) {
            log.warn("[memory-ingest] 更新事件状态失败: id={}, status={}, error={}", id, status, e.getMessage());
        }
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
    }

    private static String truncatePayload(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PAYLOAD_BYTES) {
            return payload;
        }
        log.warn("[memory-ingest] payload 超过 8KB，已截断: originalBytes={}", bytes.length);
        return new String(bytes, 0, MAX_PAYLOAD_BYTES, StandardCharsets.UTF_8);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
