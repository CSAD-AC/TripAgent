package uno.zhuchen.agent.controller;

import org.junit.jupiter.api.Test;
import uno.zhuchen.agent.core.memory.block.MemoryBlockService;
import uno.zhuchen.agent.core.memory.block.MemoryBlockType;
import uno.zhuchen.agent.domain.dto.MemoryContentRequest;
import uno.zhuchen.agent.domain.entity.MemoryBlockEntity;
import uno.zhuchen.agent.domain.entity.MemoryRawEventEntity;
import uno.zhuchen.agent.domain.entity.MemoryUserPromptEntity;
import uno.zhuchen.agent.security.UserPrincipal;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryBlockControllerTest {

    private final MemoryBlockService service = mock(MemoryBlockService.class);
    private final MemoryBlockController controller = new MemoryBlockController(service);
    private final UserPrincipal principal =
            new UserPrincipal("u1", "tester", "Tester", "hash", true, true);

    @Test
    void savesUserPromptWithoutTouchingAutoBlocks() {
        MemoryUserPromptEntity entity = new MemoryUserPromptEntity();
        entity.setUserId("u1");
        entity.setContent("回复保持简洁");
        entity.setCharCount(6);
        entity.setVersion(1);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        when(service.prompt("u1")).thenReturn(entity);
        when(service.userPromptMaxChars()).thenReturn(600);

        controller.savePrompt(new MemoryContentRequest("回复保持简洁"), principal).block();

        verify(service).saveUserPrompt("u1", "回复保持简洁");
        verify(service, never()).saveGeneratedBlock(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void savesGeneratedBlockThroughDedicatedMethod() {
        MemoryBlockEntity entity = new MemoryBlockEntity();
        entity.setId("b1");
        entity.setUserId("u1");
        entity.setBlockType("PROFILE");
        entity.setContent("自动块用户编辑：不吃辣");
        entity.setCharCount(12);
        entity.setVersion(1);
        entity.setLastChangeSource("MANUAL");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        when(service.block("u1", MemoryBlockType.PROFILE)).thenReturn(entity);
        when(service.maxChars(MemoryBlockType.PROFILE)).thenReturn(600);

        controller.saveBlock("profile", new MemoryContentRequest("自动块用户编辑：不吃辣"), principal).block();

        verify(service).saveGeneratedBlock(
                "u1", MemoryBlockType.PROFILE, "自动块用户编辑：不吃辣");
    }

    @Test
    void rejectsOversizedProfileEdit() {
        doThrow(new IllegalArgumentException("profile 超过上限 600 字"))
                .when(service).saveGeneratedBlock(
                        org.mockito.ArgumentMatchers.eq("u1"),
                        org.mockito.ArgumentMatchers.eq(MemoryBlockType.PROFILE),
                        org.mockito.ArgumentMatchers.any());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.saveBlock(
                        "profile", new MemoryContentRequest("长".repeat(601)), principal).block());

        assertTrue(error.getMessage().contains("600"));
    }

    @Test
    void returnsLatestMemoryEventResult() {
        MemoryRawEventEntity event = new MemoryRawEventEntity();
        event.setId("e1");
        event.setConversationId("c1");
        event.setSource("KEYWORD");
        event.setEventStatus("DONE");
        event.setResultStatus("UPDATED");
        event.setContent("记住：我喜欢吃辣");
        event.setCandidateJson("""
                {"profileCandidates":["喜欢吃辣"],"eventCandidates":[],"discarded":[]}
                """);
        event.setResultJson("""
                {"changed":true,"profile":"喜欢吃辣","events":""}
                """);
        event.setCreatedAt(LocalDateTime.now());
        event.setProcessedAt(LocalDateTime.now());
        when(service.latestEvent(
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(event);

        var result = controller.latestEvent(
                "c1", System.currentTimeMillis() - 60_000, principal).block();

        assertTrue(result != null && result.getData() != null);
        assertTrue("UPDATED".equals(result.getData().resultStatus()));
        assertEquals("c1", result.getData().conversationId());
        assertTrue(result.getData().candidateJson().contains("喜欢吃辣"));
        assertTrue(result.getData().resultJson().contains("\"changed\":true"));
    }

    @Test
    void returnsConversationMemoryEventsWithDetails() {
        MemoryRawEventEntity event = new MemoryRawEventEntity();
        event.setId("e1");
        event.setConversationId("c1");
        event.setSource("KEYWORD");
        event.setEventStatus("DONE");
        event.setResultStatus("UPDATED");
        event.setContent("记住：我喜欢吃辣");
        event.setCandidateJson("""
                {"profileCandidates":["喜欢吃辣"],"eventCandidates":[],"discarded":[]}
                """);
        event.setResultJson("""
                {"changed":true,"profile":"喜欢吃辣","events":""}
                """);
        event.setCreatedAt(LocalDateTime.now());
        when(service.conversationEvents(
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("c1"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(50)))
                .thenReturn(List.of(event));

        var result = controller.events("c1", null, 50, principal).block();

        assertTrue(result != null && result.getData() != null);
        assertEquals(1, result.getData().size());
        assertEquals("记住：我喜欢吃辣", result.getData().get(0).content());
    }
}
