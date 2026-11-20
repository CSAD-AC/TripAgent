package uno.zhuchen.agent.core.memory.block;

import org.junit.jupiter.api.Test;
import uno.zhuchen.agent.domain.entity.MemoryRawEventEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryRawEventMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRawEventServiceTest {

    @Test
    void explicitKeywordUsesStableEventKey() {
        MemoryRawEventMapper mapper = mock(MemoryRawEventMapper.class);
        MemoryRawEventService service = new MemoryRawEventService(mapper);

        String key1 = service.eventKey("u1", "conv-1", "记住：我不吃辣", "KEYWORD");
        String key2 = service.eventKey("u1", "conv-1", "记住：我不吃辣", "KEYWORD");
        String key3 = service.eventKey("u1", "conv-1", "记住：我不吃辣 ", "KEYWORD");

        assertEquals(key1, key2);
        assertEquals(key1, key3);
    }

    @Test
    void duplicateEventKeyIsIgnored() {
        MemoryRawEventMapper mapper = mock(MemoryRawEventMapper.class);
        when(mapper.insertIgnore(any())).thenReturn(0);
        MemoryRawEventService service = new MemoryRawEventService(mapper);

        String id = service.appendKeyword("u1", "conv-1", "记住：我不吃辣");

        assertNull(id);
        verify(mapper, never()).insert(any(MemoryRawEventEntity.class));
    }
}
