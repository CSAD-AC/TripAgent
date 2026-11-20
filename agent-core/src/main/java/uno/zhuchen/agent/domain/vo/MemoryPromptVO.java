package uno.zhuchen.agent.domain.vo;

import uno.zhuchen.agent.domain.entity.MemoryUserPromptEntity;

import java.time.LocalDateTime;

public record MemoryPromptVO(
        String content,
        int charCount,
        int maxChars,
        int version,
        LocalDateTime updatedAt
) {
    public static MemoryPromptVO from(MemoryUserPromptEntity entity, int maxChars) {
        return new MemoryPromptVO(
                entity.getContent() == null ? "" : entity.getContent(),
                entity.getCharCount() == null ? 0 : entity.getCharCount(),
                maxChars,
                entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getUpdatedAt());
    }
}
