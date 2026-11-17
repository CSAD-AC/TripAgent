package uno.zhuchen.agent.core.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MemoryExtractionResult(
        MemoryItemType type,
        String key,
        String value,
        String content,
        BigDecimal confidence,
        BigDecimal importance,
        String attributesJson,
        LocalDateTime expiresAt
) {
}
