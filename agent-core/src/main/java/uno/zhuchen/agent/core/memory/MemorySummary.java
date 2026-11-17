package uno.zhuchen.agent.core.memory;

import java.util.List;

public record MemorySummary(List<MemoryLine> lines) {

    public record MemoryLine(String label, String value) {
    }

    public boolean isEmpty() {
        return lines == null || lines.isEmpty();
    }
}
