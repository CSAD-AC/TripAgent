package uno.zhuchen.agent.core.memory.block;

import java.util.Locale;

public enum MemoryBlockType {
    PROFILE(600),
    EVENT(1000);

    private final int maxChars;

    MemoryBlockType(int maxChars) {
        this.maxChars = maxChars;
    }

    public int maxChars() {
        return maxChars;
    }

    public static MemoryBlockType fromPath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("记忆块类型不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知记忆块类型: " + value);
        }
    }
}
