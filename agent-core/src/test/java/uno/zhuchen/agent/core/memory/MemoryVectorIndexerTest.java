package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.persistence.mapper.MemoryItemMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemoryVectorIndexerTest {

    @Mock
    private MemoryVectorService vectorService;

    @Mock
    private MemoryItemService memoryItemService;

    @Mock
    private MemoryItemMapper itemMapper;

    @Test
    void pendingEpisodicItemIsIndexedAndMarked() {
        MemoryVectorIndexer indexer = new MemoryVectorIndexer(vectorService, memoryItemService, itemMapper);

        indexer.index(record(MemoryVectorStatus.PENDING));

        verify(vectorService).upsert(org.mockito.ArgumentMatchers.any());
        verify(itemMapper).update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonPendingItemIsSkipped() {
        MemoryVectorIndexer indexer = new MemoryVectorIndexer(vectorService, memoryItemService, itemMapper);

        indexer.index(record(MemoryVectorStatus.NOT_REQUIRED));

        verify(vectorService, never()).upsert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void indexFailureMarksFailedWithoutThrowing() {
        MemoryVectorIndexer indexer = new MemoryVectorIndexer(vectorService, memoryItemService, itemMapper);
        doThrow(new RuntimeException("milvus down"))
                .when(vectorService).upsert(org.mockito.ArgumentMatchers.any());

        indexer.index(record(MemoryVectorStatus.PENDING));

        verify(itemMapper).update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    private static MemoryItemRecord record(MemoryVectorStatus status) {
        return new MemoryItemRecord("e1", "default", "u1", "", MemoryItemType.EPISODIC,
                "trip.kyoto", "规划过京都行程", "trip.kyoto=规划过京都行程", null,
                "user_message", "conv", null, new BigDecimal("0.8"), new BigDecimal("0.6"),
                MemoryItemStatus.ACTIVE, null, null, status, null, null, "rule-v1",
                LocalDateTime.now(), LocalDateTime.now());
    }
}
