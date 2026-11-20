package uno.zhuchen.agent.core.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.common.exception.NotFoundException;
import uno.zhuchen.agent.domain.entity.MemoryItemEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryItemMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryItemServiceTest {

    @Mock
    private MemoryItemMapper mapper;

    private MemoryItemService service;
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new MemoryItemService(mapper);
        sequence.set(0);
    }

    @Test
    void upsertInsertsStructuredPreference() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(MemoryItemEntity.class))).thenAnswer(invocation -> {
            MemoryItemEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId("m" + sequence.incrementAndGet());
            }
            return 1;
        });

        MemoryItemRecord record = service.upsert(
                "default", "user-a", null, MemoryItemType.PREFERENCE,
                "travel.preference.food", "true", "user_message", "conv-1", null,
                new BigDecimal("0.800"), new BigDecimal("0.600"), "rule-v1");

        assertNotNull(record);
        assertEquals(MemoryItemType.PREFERENCE, record.type());
        assertEquals("travel.preference.food=true", record.content());
        assertEquals(MemoryVectorStatus.NOT_REQUIRED, record.vectorStatus());
        assertEquals("", record.projectId());
    }

    @Test
    void upsertAppendsEpisodicRecord() {
        when(mapper.insert(any(MemoryItemEntity.class))).thenAnswer(invocation -> {
            MemoryItemEntity entity = invocation.getArgument(0);
            entity.setId("m" + sequence.incrementAndGet());
            return 1;
        });

        MemoryItemRecord record = service.upsert(
                "default", "user-a", null, MemoryItemType.EPISODIC,
                "trip.visited.city", "京都", "assistant_message", "conv-1", "msg-1",
                new BigDecimal("0.700"), new BigDecimal("0.500"), "rule-v1");

        assertNotNull(record);
        assertEquals(MemoryItemType.EPISODIC, record.type());
        assertEquals(MemoryVectorStatus.PENDING, record.vectorStatus());
    }

    @Test
    void deleteRejectsForeignUser() {
        when(mapper.selectById("m1")).thenReturn(entity("m1", "user-b"));

        assertThrows(NotFoundException.class, () -> service.delete("default", "user-a", "m1"));
    }

    @Test
    void deletedKeyCanBeAddedAgain() {
        List<MemoryItemEntity> rows = new ArrayList<>();
        when(mapper.selectOne(any())).thenAnswer(invocation -> rows.stream()
                .filter(row -> MemoryItemStatus.ACTIVE.name().equals(row.getMemoryStatus()))
                .findFirst().orElse(null));
        when(mapper.insert(any(MemoryItemEntity.class))).thenAnswer(invocation -> {
            MemoryItemEntity entity = invocation.getArgument(0);
            entity.setId("m" + sequence.incrementAndGet());
            rows.add(entity);
            return 1;
        });
        when(mapper.selectById(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElse(null);
        });
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            rows.stream().filter(row -> MemoryItemStatus.ACTIVE.name().equals(row.getMemoryStatus()))
                    .forEach(row -> {
                        row.setMemoryStatus(MemoryItemStatus.DELETED.name());
                        row.setEmbeddingStatus(MemoryVectorStatus.DELETED.name());
                    });
            return 1;
        });

        service.upsert("default", "user-a", null, MemoryItemType.PREFERENCE,
                "travel.preference.food", "true", "user_message", "conv-1", null,
                new BigDecimal("0.800"), new BigDecimal("0.600"), "rule-v1");
        service.delete("default", "user-a", "m1");
        service.upsert("default", "user-a", null, MemoryItemType.PREFERENCE,
                "travel.preference.food", "true", "user_message", "conv-2", null,
                new BigDecimal("0.800"), new BigDecimal("0.600"), "rule-v1");
        service.delete("default", "user-a", "m2");

        assertEquals(2, rows.size());
        assertEquals(2, rows.stream()
                .filter(row -> MemoryItemStatus.DELETED.name().equals(row.getMemoryStatus()))
                .count());
        verify(mapper, atLeastOnce()).insert(any(MemoryItemEntity.class));
    }

    @Test
    void listFiltersByActiveStatus() {
        when(mapper.selectList(any())).thenReturn(List.of(entity("m1", "user-a")));

        List<MemoryItemRecord> records = service.list("default", "user-a", null);

        assertEquals(1, records.size());
        assertEquals("travel.preference.food", records.get(0).key());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<MemoryItemEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
    }

    private static MemoryItemEntity entity(String id, String userId) {
        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setUserId(userId);
        entity.setProjectId("");
        entity.setMemoryType(MemoryItemType.PREFERENCE.name());
        entity.setMemoryKey("travel.preference.food");
        entity.setMemoryValue("true");
        entity.setContent("travel.preference.food=true");
        entity.setMemoryStatus(MemoryItemStatus.ACTIVE.name());
        entity.setEmbeddingStatus(MemoryVectorStatus.NOT_REQUIRED.name());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
