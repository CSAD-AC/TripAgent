package uno.zhuchen.agent.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryTriggerServiceTest {

    @Mock
    private MemoryIngestService ingestService;

    private MemoryTriggerService service;

    @BeforeEach
    void setUp() {
        service = new MemoryTriggerService(ingestService, new ObjectMapper(), true, 6);
    }

    @Test
    void explicitPreferenceTriggersIngest() {
        when(ingestService.enqueue(any(), any(), any(), any(), any())).thenReturn("event-1");

        String eventId = service.onConversationFinished("u1", "conv-1", "我喜欢美食，预算通常5000元");

        assertNotNull(eventId);
        verify(ingestService).enqueue(eq("default"), eq("u1"), eq("conv-1"), eq("message"), any());
    }

    @Test
    void longTravelContextTriggersIngest() {
        when(ingestService.enqueue(any(), any(), any(), any(), any())).thenReturn("event-2");

        String eventId = service.onConversationFinished("u1", "conv-1", "帮我规划一个适合亲子的云南旅行行程，不要太赶");

        assertNotNull(eventId);
    }

    @Test
    void sensitiveOrShortMessageDoesNotTrigger() {
        assertNull(service.onConversationFinished("u1", "conv-1", "好"));
        assertNull(service.onConversationFinished("u1", "conv-1", "我的身份证号和银行卡密码是"));
    }
}
