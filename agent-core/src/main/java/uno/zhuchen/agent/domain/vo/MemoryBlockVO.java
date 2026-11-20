package uno.zhuchen.agent.domain.vo;

import uno.zhuchen.agent.domain.entity.MemoryBlockEntity;

import java.time.LocalDateTime;

public record MemoryBlockVO(
        String type,
        String content,
        int charCount,
        int maxChars,
        int version,
        String changeSource,
        LocalDateTime changedAt,
        LocalDateTime updatedAt,
        boolean generatedEdited
) {
    public static MemoryBlockVO from(MemoryBlockEntity entity, int maxChars) {
        return new MemoryBlockVO(
                entity.getBlockType().toLowerCase(),
                entity.getContent() == null ? "" : entity.getContent(),
                entity.getCharCount() == null ? 0 : entity.getCharCount(),
                maxChars,
                entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getLastChangeSource() == null ? "INIT" : entity.getLastChangeSource(),
                entity.getLastChangedAt(),
                entity.getUpdatedAt(),
                entity.getGeneratedEditedAt() != null);
    }
}
