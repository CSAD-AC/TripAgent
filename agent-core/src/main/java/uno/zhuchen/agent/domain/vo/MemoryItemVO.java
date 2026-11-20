package uno.zhuchen.agent.domain.vo;

import uno.zhuchen.agent.core.memory.MemoryItemRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MemoryItemVO(
        String id,
        String memoryType,
        String key,
        String label,
        String value,
        String content,
        BigDecimal confidence,
        BigDecimal importance,
        String status,
        String sourceConversationId,
        String extractorVersion,
        LocalDateTime updatedAt
) {
    public static MemoryItemVO from(MemoryItemRecord item) {
        String label = MemoryLabels.labelFor(item.key());
        if (label == null || label.isBlank() || label.equals(item.key())) {
            label = switch (item.type()) {
                case PROFILE -> "用户信息";
                case PREFERENCE -> "偏好";
                case CONSTRAINT -> "规划约束";
                case VETO -> "不要再记住";
                case EPISODIC -> "经历";
            };
        }
        return new MemoryItemVO(
                item.id(),
                item.type().name(),
                item.key(),
                label,
                item.value(),
                item.content(),
                item.confidence(),
                item.importance(),
                item.status().name(),
                item.sourceConversationId(),
                item.extractorVersion(),
                item.updatedAt());
    }
}
