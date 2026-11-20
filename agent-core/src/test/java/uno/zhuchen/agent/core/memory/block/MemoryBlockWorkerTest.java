package uno.zhuchen.agent.core.memory.block;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MemoryBlockWorkerTest {

    @Test
    void processesPendingEventsInBatches() {
        MemoryBlockService service = mock(MemoryBlockService.class);
        MemoryBlockWorker worker = new MemoryBlockWorker(service, 50);

        worker.run();

        verify(service).processPending(50);
    }
}
