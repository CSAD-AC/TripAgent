package uno.zhuchen.agent.core.memory.block;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uno.zhuchen.agent.domain.entity.MemoryBlockEntity;
import uno.zhuchen.agent.domain.entity.MemoryBlockRevisionEntity;
import uno.zhuchen.agent.domain.entity.MemoryRawEventEntity;
import uno.zhuchen.agent.domain.entity.MemoryUserPromptEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryBlockMapper;
import uno.zhuchen.agent.persistence.mapper.MemoryBlockRevisionMapper;
import uno.zhuchen.agent.persistence.mapper.MemoryRawEventMapper;
import uno.zhuchen.agent.persistence.mapper.MemoryUserPromptMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryBlockServiceTest {

    private final MemoryBlockMapper blockMapper = mock(MemoryBlockMapper.class);
    private final MemoryUserPromptMapper promptMapper = mock(MemoryUserPromptMapper.class);
    private final MemoryRawEventMapper rawEventMapper = mock(MemoryRawEventMapper.class);
    private final MemoryBlockRevisionMapper revisionMapper = mock(MemoryBlockRevisionMapper.class);
    private final MemoryCandidateExtractor extractor = mock(MemoryCandidateExtractor.class);
    private final MemoryBlockUpdater updater = mock(MemoryBlockUpdater.class);
    private final MemoryBlockService service = new MemoryBlockService(
            blockMapper, promptMapper, rawEventMapper, revisionMapper,
            extractor, updater, new ObjectMapper(), 600, 600, 1000, 3);

    @Test
    void savingUserPromptNeverTouchesAutomaticBlocks() {
        when(promptMapper.selectById("u1")).thenReturn(null);

        service.saveUserPrompt("u1", "称呼我为老板");

        verify(promptMapper).insert(any(MemoryUserPromptEntity.class));
        verify(revisionMapper).insert(any(MemoryBlockRevisionEntity.class));
        verifyNoInteractions(blockMapper, rawEventMapper, extractor, updater);
    }

    @Test
    void savingGeneratedBlockMarksUserEditAndWritesRevision() {
        MemoryBlockEntity existing = new MemoryBlockEntity();
        existing.setId("b1");
        existing.setUserId("u1");
        existing.setBlockType("PROFILE");
        existing.setContent("旧内容");
        existing.setCharCount(3);
        existing.setVersion(2);
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        when(blockMapper.selectOne(any())).thenReturn(existing);

        service.saveGeneratedBlock("u1", MemoryBlockType.PROFILE, "用户编辑：不吃辣");

        ArgumentCaptor<MemoryBlockEntity> captor = ArgumentCaptor.forClass(MemoryBlockEntity.class);
        verify(blockMapper).updateById(captor.capture());
        MemoryBlockEntity updated = captor.getValue();
        assertEquals(3, updated.getVersion());
        assertEquals("MANUAL", updated.getLastChangeSource());
        assertNotNull(updated.getGeneratedEditedAt());
        verify(promptMapper, never()).updateById(any(MemoryUserPromptEntity.class));
        verify(revisionMapper).insert(any(MemoryBlockRevisionEntity.class));
    }

    @Test
    void processPendingRunsExtractionBeforeUpdate() {
        MemoryRawEventEntity event = new MemoryRawEventEntity();
        event.setId("e1");
        event.setUserId("u1");
        event.setContent("记住：我不吃辣");
        event.setSource("KEYWORD");
        event.setEventStatus("PENDING");
        event.setCreatedAt(LocalDateTime.now());

        MemoryBlockEntity profile = block("PROFILE", "");
        MemoryBlockEntity events = block("EVENT", "");
        when(rawEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(event), List.of(event));
        when(rawEventMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(blockMapper.selectOne(any(Wrapper.class))).thenReturn(profile, events);
        when(extractor.extract(anyList())).thenReturn(
                new MemoryCandidateResult(List.of("不吃辣"), List.of(), List.of()));
        when(updater.update(any(), any(), any(), anyBoolean(), eq("KEYWORD")))
                .thenReturn(new MemoryBlockUpdateResult("不吃辣", "", true));

        service.processPending(10);

        verify(extractor).extract(List.of("记住：我不吃辣"));
        verify(updater).update("", "", new MemoryCandidateResult(
                List.of("不吃辣"), List.of(), List.of()), false, "KEYWORD");
        verify(blockMapper).updateById(profile);
        verify(rawEventMapper, org.mockito.Mockito.atLeast(2)).update(any(), any(Wrapper.class));
    }

    @Test
    void conversationEventsOnlyReadsExplicitEventsForOwnedConversation() {
        MemoryRawEventEntity event = new MemoryRawEventEntity();
        event.setId("e1");
        event.setUserId("u1");
        event.setConversationId("c1");
        event.setSource("KEYWORD");
        event.setEventStatus("DONE");
        when(rawEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(event));

        List<MemoryRawEventEntity> result = service.conversationEvents(
                "u1", "c1", null, 20);

        assertEquals(List.of(event), result);
        verify(rawEventMapper).selectList(any(Wrapper.class));
    }

    private static MemoryBlockEntity block(String type, String content) {
        MemoryBlockEntity entity = new MemoryBlockEntity();
        entity.setId(type);
        entity.setUserId("u1");
        entity.setBlockType(type);
        entity.setContent(content);
        entity.setCharCount(content.length());
        entity.setVersion(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
