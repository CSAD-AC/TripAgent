package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NoopMemoryVectorServiceTest {

    @Test
    void noopMethodsNeverThrow() {
        MemoryVectorService service = new NoopMemoryVectorService();

        assertDoesNotThrow(() -> service.upsert(null));
        assertDoesNotThrow(() -> service.delete("m1"));
        assertDoesNotThrow(() -> service.rebuild("default", "user-a"));
    }
}
