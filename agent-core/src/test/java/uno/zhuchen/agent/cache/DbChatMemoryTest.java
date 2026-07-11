package uno.zhuchen.agent.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.zhuchen.agent.domain.entity.MessageEntity;
import uno.zhuchen.agent.persistence.cache.DbChatMemory;
import uno.zhuchen.agent.persistence.mapper.ConversationMapper;
import uno.zhuchen.agent.persistence.mapper.MessageMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import uno.zhuchen.agent.domain.entity.ConversationEntity;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbChatMemoryTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ValueOperations<String, String> valueOps;

    private DbChatMemory dbChatMemory;

    @BeforeEach
    void setUp() {
        dbChatMemory = new DbChatMemory(conversationMapper, messageMapper,
                redisTemplate, eventPublisher);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void save_shouldPersistMessagesAndPublishEvent() {
        // given
        String conversationId = "test-conv-123";
        List<Message> messages = List.of(
                new UserMessage("北京天气如何？"),
                new AssistantMessage("北京今天晴，25°C")
        );

        // when
        dbChatMemory.save(conversationId, messages);

        // then - verify conversation insert
        verify(conversationMapper).insert(argThat((ConversationEntity conv) ->
                conv.getId().equals(conversationId)
                        && conv.getTitle().equals("北京天气如何？")
        ));

        // then - verify batch insert of messages
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageMapper).insertBatch(captor.capture());
        List<MessageEntity> entities = captor.getValue();
        assertEquals(2, entities.size());
        assertEquals("user", entities.get(0).getRole());
        assertEquals("北京天气如何？", entities.get(0).getContent());
        assertEquals("assistant", entities.get(1).getRole());
        assertEquals("北京今天晴，25°C", entities.get(1).getContent());

        // then - verify atomic increment
        verify(conversationMapper).incrementMessageCount(conversationId, 2);

        // then - verify event published
        verify(eventPublisher).publishEvent(any(DbChatMemory.ChatSaveEvent.class));
    }

    @Test
    void save_whenConversationAlreadyExists_shouldSkipInsert() {
        // given
        String conversationId = "test-conv-456";
        doThrow(new DuplicateKeyException("Duplicate entry"))
                .when(conversationMapper).insert(any(ConversationEntity.class));

        // when
        dbChatMemory.save(conversationId, List.of(new UserMessage("hello")));

        // then - still proceed with message insert (no exception)
        verify(messageMapper).insertBatch(any());
        verify(conversationMapper).incrementMessageCount(conversationId, 1);
    }

    @Test
    void save_withToolResponses_shouldSummarizeContent() {
        // given
        String conversationId = "test-conv-tool";
        List<Message> messages = List.of(
                new UserMessage("北京天气"),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "weather-id", "amapWeather", "北京 晴 25°C")))
                        .build()
        );

        // when
        dbChatMemory.save(conversationId, messages);

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageMapper).insertBatch(captor.capture());
        List<MessageEntity> entities = captor.getValue();

        assertEquals(2, entities.size());
        assertEquals("user", entities.get(0).getRole());
        assertEquals("tool", entities.get(1).getRole());
        assertEquals("amapWeather(北京 晴 25°C)", entities.get(1).getContent());
    }

    @Test
    void load_shouldHitCacheFirst() {
        // given
        String conversationId = "test-conv-load";
        String cachedJson = "[{\"role\":\"user\",\"content\":\"hello\",\"metadata\":{}}]";
        when(valueOps.get("session:" + conversationId)).thenReturn(cachedJson);

        // when
        List<Message> result = dbChatMemory.load(conversationId);

        // then - cache hit, no DB query
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(MessageType.USER, result.get(0).getMessageType());
        verify(messageMapper, org.mockito.Mockito.never()).listByConversation(anyString());
    }

    @Test
    void load_whenCacheMiss_shouldQueryDbAndBackfill() {
        // given
        String conversationId = "test-conv-miss";
        when(valueOps.get("session:" + conversationId)).thenReturn(null);

        // when
        List<Message> result = dbChatMemory.load(conversationId);

        // then
        assertNotNull(result);
        verify(messageMapper).listByConversation(conversationId);
    }

    @Test
    void load_whenRedisDown_shouldFallbackToDb() {
        // given
        String conversationId = "test-conv-down";
        when(valueOps.get("session:" + conversationId)).thenThrow(new RuntimeException("Connection refused"));

        // when - should not throw
        List<Message> result = dbChatMemory.load(conversationId);

        // then
        assertNotNull(result);
        verify(messageMapper).listByConversation(conversationId);
    }

    @Test
    void clear_shouldDeleteMessagesAndSoftDeleteConversation() {
        // given
        String conversationId = "test-conv-clear";
        var conv = new ConversationEntity();
        conv.setId(conversationId);
        conv.setStatus("active");
        when(conversationMapper.selectById(conversationId)).thenReturn(conv);

        // when
        dbChatMemory.clear(conversationId);

        // then
        verify(messageMapper).delete(any());
        verify(redisTemplate).delete("session:" + conversationId);
        verify(conversationMapper).updateById(argThat((ConversationEntity c) ->
                "deleted".equals(c.getStatus()) && c.getMessageCount() == 0));
    }
}
