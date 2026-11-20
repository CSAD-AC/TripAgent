package uno.zhuchen.agent.domain.vo;

import uno.zhuchen.agent.domain.entity.MemoryBlockRevisionEntity;

import java.time.LocalDateTime;

public record MemoryBlockRevisionVO(
        String id,
        String blockType,
        String oldContent,
        String newContent,
        String changeSource,
        LocalDateTime createdAt
) {
    public static MemoryBlockRevisionVO from(MemoryBlockRevisionEntity entity) {
        return new MemoryBlockRevisionVO(
                entity.getId(),
                entity.getBlockType(),
                entity.getOldContent(),
                entity.getNewContent(),
                entity.getChangeSource(),
                entity.getCreatedAt());
    }
}
