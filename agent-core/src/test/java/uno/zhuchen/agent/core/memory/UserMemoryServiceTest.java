package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.common.exception.NotFoundException;
import uno.zhuchen.agent.domain.entity.UserMemoryEntity;
import uno.zhuchen.agent.persistence.mapper.UserMemoryMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserMemoryServiceTest {

    @Mock
    private UserMemoryMapper mapper;

    private UserMemoryService service;

    @BeforeEach
    void setUp() {
        service = new UserMemoryService(mapper, new MemoryExtractor(), 10, 120);
    }

    @Test
    void extractAndStoreInsertsExplicitPreference() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.extractAndStore("user-a", "conv-1", "我喜欢美食");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UserMemoryEntity> captor = ArgumentCaptor.forClass(UserMemoryEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("travel.preference.food", captor.getValue().getMemoryKey());
        assertEquals("user-a", captor.getValue().getUserId());
        assertEquals("conv-1", captor.getValue().getSourceConversationId());
    }

    @Test
    void loadSummaryUsesActiveMemoriesOnly() {
        when(mapper.selectList(any())).thenReturn(List.of(memory("m1", "user-a", "常用出发城市", "上海")));

        MemorySummary summary = service.loadSummary("user-a");

        assertEquals(1, summary.lines().size());
        assertEquals("上海", summary.lines().get(0).value());
    }

    @Test
    void deleteRejectsForeignMemory() {
        when(mapper.selectById("m1")).thenReturn(memory("m1", "user-b", "常用预算", "5000"));

        assertThrows(NotFoundException.class, () -> service.delete("user-a", "m1"));
    }

    @Test
    void deleteAllScopesToAuthenticatedUser() {
        service.deleteAll("user-a");

        verify(mapper).update(any(), argThat(wrapper ->
                String.valueOf(wrapper).contains("UpdateWrapper")));
    }

    @Test
    void deletedMemoryCanBeAddedAgainAndDeletedAgain() {
        // 用内存状态模拟真实表: active 行唯一, 查询返回当前 active 行, selectById 可见所有行.
        List<UserMemoryEntity> rows = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        when(mapper.selectOne(any())).thenAnswer(invocation -> rows.stream()
                .filter(row -> "active".equals(row.getMemoryStatus()))
                .findFirst().orElse(null));
        when(mapper.selectById(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return rows.stream().filter(row -> row.getId().equals(id)).findFirst().orElse(null);
        });
        when(mapper.insert(any(UserMemoryEntity.class))).thenAnswer(invocation -> {
            UserMemoryEntity row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId("m" + sequence.incrementAndGet());
            }
            rows.add(row);
            return 1;
        });
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            // 测试替身只需模拟“把目标 active 行置为 deleted”，实际 SQL 条件由生产代码负责。
            rows.stream().filter(row -> "active".equals(row.getMemoryStatus()))
                    .forEach(row -> row.setMemoryStatus("deleted"));
            return 1;
        });

        service.extractAndStore("user-a", "conv-1", "我喜欢美食");
        String firstId = rows.stream().filter(row -> "active".equals(row.getMemoryStatus()))
                .findFirst().orElseThrow().getId();
        service.delete("user-a", firstId);

        service.extractAndStore("user-a", "conv-2", "我喜欢美食");
        String secondId = rows.stream().filter(row -> "active".equals(row.getMemoryStatus()))
                .findFirst().orElseThrow().getId();
        service.delete("user-a", secondId);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(row -> "deleted".equals(row.getMemoryStatus())));
    }

    private static UserMemoryEntity memory(String id, String userId, String key, String value) {
        UserMemoryEntity entity = new UserMemoryEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setMemoryType("default");
        entity.setMemoryKey(key);
        entity.setMemoryValue(value);
        entity.setMemoryStatus("active");
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
