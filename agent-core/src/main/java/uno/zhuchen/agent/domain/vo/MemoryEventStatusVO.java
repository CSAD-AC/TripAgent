package uno.zhuchen.agent.domain.vo;

import uno.zhuchen.agent.domain.entity.MemoryRawEventEntity;

import java.time.LocalDateTime;

public record MemoryEventStatusVO(
        String id,
        String conversationId,
        String source,
        String eventStatus,
        String resultStatus,
        String content,
        String candidateJson,
        String resultJson,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime processedAt
) {
    public static MemoryEventStatusVO from(MemoryRawEventEntity entity) {
        return new MemoryEventStatusVO(
                entity.getId(),
                entity.getConversationId(),
                entity.getSource(),
                entity.getEventStatus(),
                entity.getResultStatus(),
                entity.getContent(),
                entity.getCandidateJson(),
                entity.getResultJson(),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getProcessedAt());
    }
}
