package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryRetrievalServiceTest {

    @Test
    void vetoSuppressesMatchingMemory() {
        MemoryItemService itemService = mock(MemoryItemService.class);
        MemoryVectorService vectorService = mock(MemoryVectorService.class);
        when(itemService.list(any(), any(), any())).thenReturn(List.of(
                item("p1", MemoryItemType.PREFERENCE, "旅行偏好：美食", "travel.preference.food=true"),
                item("v1", MemoryItemType.VETO, "不要再提美食", "veto.food")));
        MemoryRetrievalService service = new MemoryRetrievalService(
                itemService, vectorService, true, 20, 6, 0.0);

        MemoryPromptContext context = service.load("default", "u1", null, "吃什么");

        assertTrue(context.pinnedLines().stream().noneMatch(line -> line.contains("美食：")));
        assertEquals(1, context.suppressions().size());
    }

    @Test
    void episodicRecallUsesLexicalSignal() {
        MemoryItemService itemService = mock(MemoryItemService.class);
        MemoryVectorService vectorService = mock(MemoryVectorService.class);
        when(itemService.list(any(), any(), any())).thenReturn(List.of(
                item("e1", MemoryItemType.EPISODIC, "规划过京都行程", "trip.kyoto"),
                item("e2", MemoryItemType.EPISODIC, "规划过成都行程", "trip.chengdu")));
        MemoryRetrievalService service = new MemoryRetrievalService(
                itemService, vectorService, true, 20, 6, 0.0);

        MemoryPromptContext context = service.load("default", "u1", null, "京都");

        assertEquals("经历：规划过京都行程", context.episodicLines().get(0));
    }

    @Test
    void episodicRecallPrefersVectorHitsWhenAvailable() {
        MemoryItemService itemService = mock(MemoryItemService.class);
        MemoryVectorService vectorService = mock(MemoryVectorService.class);
        when(vectorService.available()).thenReturn(true);
        when(vectorService.search(any(), any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(new MemoryVectorHit("e2", 0.93, "规划过成都行程")));
        when(itemService.list(any(), any(), any())).thenReturn(List.of(
                item("e1", MemoryItemType.EPISODIC, "规划过京都行程", "trip.kyoto"),
                item("e2", MemoryItemType.EPISODIC, "规划过成都行程", "trip.chengdu")));
        MemoryRetrievalService service = new MemoryRetrievalService(
                itemService, vectorService, true, 20, 6, 0.0);

        MemoryPromptContext context = service.load("default", "u1", null, "带娃看熊猫");

        assertEquals("经历：规划过成都行程", context.episodicLines().get(0));
    }

    private static MemoryItemRecord item(String id, MemoryItemType type, String value, String key) {
        return new MemoryItemRecord(id, "default", "u1", "", type, key, value, key + "=" + value,
                null, "user_message", "conv", "msg", new BigDecimal("0.8"),
                new BigDecimal("0.6"), MemoryItemStatus.ACTIVE, null, null,
                MemoryVectorStatus.NOT_REQUIRED, null, null, "rule-v1",
                LocalDateTime.now(), LocalDateTime.now());
    }
}
