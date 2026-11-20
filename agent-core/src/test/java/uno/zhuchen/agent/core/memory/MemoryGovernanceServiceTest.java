package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.common.exception.NotFoundException;
import uno.zhuchen.agent.domain.entity.MemoryItemEntity;
import uno.zhuchen.agent.domain.entity.MemoryRevisionEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryItemMapper;
import uno.zhuchen.agent.persistence.mapper.MemoryRevisionMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryGovernanceServiceTest {

    @Mock
    private MemoryItemMapper itemMapper;

    @Mock
    private MemoryItemService itemService;

    @Mock
    private MemoryRevisionMapper revisionMapper;

    @Mock
    private MemoryVectorService vectorService;

    @Test
    void correctWritesRevisionAndDeletesVector() {
        MemoryGovernanceService service = new MemoryGovernanceService(
                itemMapper, itemService, revisionMapper, vectorService);
        when(itemMapper.selectById("m1")).thenReturn(owned());

        service.correct("default", "u1", "m1", "false");

        verify(itemMapper).update(any(), any());
        verify(revisionMapper).insert(any(MemoryRevisionEntity.class));
        verify(vectorService).delete("m1");
    }

    @Test
    void revisionsRejectForeignUser() {
        MemoryGovernanceService service = new MemoryGovernanceService(
                itemMapper, itemService, revisionMapper, vectorService);
        when(itemMapper.selectById("m1")).thenReturn(owned());

        assertThrows(NotFoundException.class, () -> service.revisions("default", "u2", "m1"));
    }

    private static MemoryItemEntity owned() {
        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setId("m1");
        entity.setTenantId("default");
        entity.setUserId("u1");
        entity.setProjectId("");
        entity.setMemoryKey("travel.preference.food");
        entity.setMemoryValue("true");
        entity.setMemoryStatus(MemoryItemStatus.ACTIVE.name());
        return entity;
    }
}
