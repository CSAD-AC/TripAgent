package uno.zhuchen.agent.domain.vo;

import uno.zhuchen.agent.domain.entity.UserMemoryEntity;

import java.time.LocalDateTime;

public record UserMemoryVO(
        String id,
        String memoryType,
        String label,
        String value,
        String sourceConversationId,
        LocalDateTime updatedAt
) {

    public static UserMemoryVO from(UserMemoryEntity entity) {
        return new UserMemoryVO(
                entity.getId(),
                entity.getMemoryType(),
                MemoryLabels.labelFor(entity.getMemoryKey()),
                entity.getMemoryValue(),
                entity.getSourceConversationId(),
                entity.getUpdatedAt());
    }
}
