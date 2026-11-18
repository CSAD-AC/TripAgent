package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoopMemoryVectorServiceTest {

    @Test
    void noopMethodsNeverThrow() {
        MemoryVectorService service = new NoopMemoryVectorService();

        assertDoesNotThrow(() -> service.upsert(null));
        assertDoesNotThrow(() -> service.delete("m1"));
        assertFalse(service.available());
        assertTrue(service.search("default", "user-a", null, "京都", 5, 0.6).isEmpty());
    }
}
