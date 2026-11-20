package uno.zhuchen.agent.core.memory;

import java.util.List;

public record MemoryPromptContext(
        List<String> pinnedLines,
        List<String> episodicLines,
        List<String> suppressions
) {
    public static MemoryPromptContext empty() {
        return new MemoryPromptContext(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return pinnedLines.isEmpty() && episodicLines.isEmpty();
    }
}
