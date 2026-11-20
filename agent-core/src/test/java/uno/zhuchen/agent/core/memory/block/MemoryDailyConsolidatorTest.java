package uno.zhuchen.agent.core.memory.block;

import org.junit.jupiter.api.Test;
import uno.zhuchen.agent.domain.entity.MessageEntity;
import uno.zhuchen.agent.persistence.query.MessageQuery;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryDailyConsolidatorTest {

    @Test
    void skipsUsersWithLessThanFiveNewMessages() {
        MemoryWatermarkService watermarkService = mock(MemoryWatermarkService.class);
        MessageQuery messageQuery = mock(MessageQuery.class);
        MemoryRawEventService rawEventService = mock(MemoryRawEventService.class);
        when(watermarkService.activeUserIds()).thenReturn(List.of("u1"));
        when(watermarkService.lastMessageId("u1")).thenReturn(10L);
        when(messageQuery.newUserMessagesAfter("u1", 10L, 200))
                .thenReturn(List.of(msg(11L), msg(12L), msg(13L), msg(14L)));

        MemoryDailyConsolidator daily = new MemoryDailyConsolidator(
                watermarkService, messageQuery, rawEventService, 5);
        daily.dailyRun();

        verify(rawEventService, never()).appendDaily(any(), any(), any(), any());
        verify(watermarkService).touch(eq("u1"), eq(10L), any());
    }

    @Test
    void appendsFiveNewMessagesOnce() {
        MemoryWatermarkService watermarkService = mock(MemoryWatermarkService.class);
        MessageQuery messageQuery = mock(MessageQuery.class);
        MemoryRawEventService rawEventService = mock(MemoryRawEventService.class);
        when(watermarkService.activeUserIds()).thenReturn(List.of("u1"));
        when(watermarkService.lastMessageId("u1")).thenReturn(10L);
        when(messageQuery.newUserMessagesAfter("u1", 10L, 200))
                .thenReturn(List.of(msg(11L), msg(12L), msg(13L), msg(14L), msg(15L)));

        MemoryDailyConsolidator daily = new MemoryDailyConsolidator(
                watermarkService, messageQuery, rawEventService, 5);
        daily.dailyRun();

        verify(rawEventService, times(5)).appendDaily(eq("u1"), any(), anyLong(), any());
        verify(watermarkService).touch(eq("u1"), eq(15L), any());
    }

    private static MessageEntity msg(long id) {
        MessageEntity entity = new MessageEntity();
        entity.setId(id);
        entity.setConversationId("c1");
        entity.setContent("message-" + id);
        return entity;
    }
}
