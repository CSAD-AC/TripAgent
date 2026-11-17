package uno.zhuchen.agent.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.domain.entity.MemoryIngestEventEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryExtractionWorkerTest {

    @Mock
    private MemoryIngestService ingestService;

    @Mock
    private MemoryItemService memoryItemService;

    private MemoryExtractionWorker worker;

    @BeforeEach
    void setUp() {
        RuleMemoryCandidateExtractor extractor =
                new RuleMemoryCandidateExtractor(new MemoryExtractor(), new ObjectMapper());
        worker = new MemoryExtractionWorker(
                ingestService, extractor, memoryItemService, new ObjectMapper());
    }

    @Test
    void processPendingBatchWritesCandidatesAndMarksDone() {
        MemoryIngestEventEntity event = event("event-1", "{\"message\":\"我喜欢美食\"}");
        when(ingestService.claimBatch(10)).thenReturn(List.of(event));

        int processed = worker.processPendingBatch(10);

        assertEquals(1, processed);
        verify(memoryItemService).upsert(
                eq("default"), eq("user-a"), eq(null), eq(MemoryItemType.PREFERENCE),
                eq("travel.preference.food"), eq("true"), eq("user_message"),
                eq("conv-1"), eq(null), any(BigDecimal.class), any(BigDecimal.class), eq("rule-v1"));
        verify(ingestService).markDone("event-1");
    }

    @Test
    void processPendingBatchSkipsWhenNoCandidate() {
        when(ingestService.claimBatch(10)).thenReturn(List.of(event("event-2", "{\"message\":\"随便\"}")));

        int processed = worker.processPendingBatch(10);

        assertEquals(0, processed);
        verify(ingestService).markSkipped("event-2");
    }

    private static MemoryIngestEventEntity event(String id, String payload) {
        MemoryIngestEventEntity event = new MemoryIngestEventEntity();
        event.setId(id);
        event.setTenantId("default");
        event.setUserId("user-a");
        event.setConversationId("conv-1");
        event.setEventType("message");
        event.setPayloadJson(payload);
        event.setEventStatus("processing");
        return event;
    }
}
