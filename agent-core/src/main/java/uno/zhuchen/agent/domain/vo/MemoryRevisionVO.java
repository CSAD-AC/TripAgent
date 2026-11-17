package uno.zhuchen.agent.domain.vo;

import uno.zhuchen.agent.domain.entity.MemoryRevisionEntity;

import java.time.LocalDateTime;

public record MemoryRevisionVO(
        String id,
        String memoryItemId,
        String operation,
        String previousValue,
        String newValue,
        String reason,
        LocalDateTime createdAt
) {
    public static MemoryRevisionVO from(MemoryRevisionEntity entity) {
        return new MemoryRevisionVO(
                entity.getId(),
                entity.getMemoryItemId(),
                entity.getOperation(),
                entity.getPreviousValue(),
                entity.getNewValue(),
                entity.getReason(),
                entity.getCreatedAt());
    }
}
