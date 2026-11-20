package uno.zhuchen.agent.core.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MemoryItemRecord(
        String id,
        String tenantId,
        String userId,
        String projectId,
        MemoryItemType type,
        String key,
        String value,
        String content,
        String attributesJson,
        String sourceType,
        String sourceConversationId,
        String sourceMessageId,
        BigDecimal confidence,
        BigDecimal importance,
        MemoryItemStatus status,
        String supersededBy,
        LocalDateTime expiresAt,
        MemoryVectorStatus vectorStatus,
        String embeddingModel,
        String embeddingVersion,
        String extractorVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
