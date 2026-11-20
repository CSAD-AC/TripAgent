package uno.zhuchen.agent.core.memory.block;

public record MemoryBlockUpdateResult(
        String profile,
        String events,
        boolean generatedEditPreserved
) {
}
