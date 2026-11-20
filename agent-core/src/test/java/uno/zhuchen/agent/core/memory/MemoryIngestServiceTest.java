package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.domain.entity.MemoryIngestEventEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryIngestEventMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryIngestServiceTest {

    @Mock
    private MemoryIngestEventMapper mapper;

    private MemoryIngestService service;

    @BeforeEach
    void setUp() {
        service = new MemoryIngestService(mapper);
    }

    @Test
    void enqueueCreatesPendingEvent() {
        String id = service.enqueue("default", "user-a", "conv-1", "message", "{\"message\":\"我喜欢美食\"}");

        assertNotNull(id);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MemoryIngestEventEntity> captor = ArgumentCaptor.forClass(MemoryIngestEventEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("pending", captor.getValue().getEventStatus());
        assertEquals("user-a", captor.getValue().getUserId());
    }

    @Test
    void enqueueTruncatesLargePayload() {
        String payload = "x".repeat(9000);

        service.enqueue("default", "user-a", "conv-1", "message", payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MemoryIngestEventEntity> captor = ArgumentCaptor.forClass(MemoryIngestEventEntity.class);
        verify(mapper).insert(captor.capture());
        assertTrue(captor.getValue().getPayloadJson().getBytes(StandardCharsets.UTF_8).length <= 8192);
    }

    @Test
    void claimBatchMarksEventsProcessing() {
        MemoryIngestEventEntity event = new MemoryIngestEventEntity();
        event.setId("event-1");
        event.setEventStatus("pending");
        when(mapper.selectList(any())).thenReturn(List.of(event));

        List<MemoryIngestEventEntity> claimed = service.claimBatch(10);

        assertEquals(1, claimed.size());
        verify(mapper).update(any(), any());
    }
}
