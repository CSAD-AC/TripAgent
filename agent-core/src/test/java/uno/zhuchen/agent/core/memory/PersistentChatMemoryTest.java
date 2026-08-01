package uno.zhuchen.agent.core.memory;

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
import uno.zhuchen.agent.domain.entity.ConversationEntity;
import uno.zhuchen.agent.domain.entity.MessageEntity;
import uno.zhuchen.agent.persistence.cache.CacheMessageDto;
import uno.zhuchen.agent.persistence.cache.RedisMessageCache;
import uno.zhuchen.agent.persistence.cache.SessionMessageSavedEvent;
import uno.zhuchen.agent.persistence.mapper.ConversationMapper;
import uno.zhuchen.agent.persistence.mapper.MessageMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆控制层(生产 profile)的单元测试。
 *
 * <p>覆盖的协作链路:
 * <ul>
 *   <li>save: Conversation 幂等 + message INSERT IGNORE + count 自增 + 发布 SessionMessageSavedEvent</li>
 *   <li>load: 缓存命中 / 未命中走 MySQL 并回填 / 缓存不可用降级</li>
 *   <li>clear: 物理删除 message + 软删除 conversation + 清缓存</li>
 * </ul>
 *
 * <p>不覆盖(单元测试天然限制):@TransactionalEventListener AFTER_COMMIT 实际触发的缓存写,
 * 这里只验证编排顺序(事件被发布)与参数。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersistentChatMemoryTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private RedisMessageCache messageCache;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PersistentChatMemory persistentChatMemory;

    private static final long CACHE_TTL_SECONDS = 1800L;

    @BeforeEach
    void setUp() {
        persistentChatMemory = new PersistentChatMemory(
                messageMapper, conversationMapper, messageCache, eventPublisher, CACHE_TTL_SECONDS);
    }

    // ============= save =============

    @Test
    void save_shouldPersistMessagesAndPublishEvent() {
        // given
        String conversationId = "test-conv-123";
        List<Message> messages = List.of(
                new UserMessage("北京天气如何？"),
                new AssistantMessage("北京今天晴，25°C")
        );

        // when
        persistentChatMemory.save(conversationId, messages, "a1b2c3d4");

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
        // P0 回归: 自定义 @Insert 不触发自动填充, created_at 必须显式赋值
        assertNotNull(entities.get(0).getCreatedAt());
        assertNotNull(entities.get(1).getCreatedAt());

        // then - verify atomic increment
        verify(conversationMapper).incrementMessageCount(conversationId, 2);

        // then - verify SessionMessageSavedEvent published (缓存层 AFTER_COMMIT 监听)
        ArgumentCaptor<SessionMessageSavedEvent> eventCaptor = ArgumentCaptor.forClass(SessionMessageSavedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        SessionMessageSavedEvent event = eventCaptor.getValue();
        assertEquals(conversationId, event.getConversationId());
        // 事件载荷是 DTO 列表(2 条),验证转换正确
        assertEquals(2, event.getDtos().size());
        assertEquals("user", event.getDtos().get(0).getRole());
        assertEquals("assistant", event.getDtos().get(1).getRole());
    }

    @Test
    void save_whenConversationAlreadyExists_shouldSkipInsert() {
        // given
        String conversationId = "test-conv-456";
        doThrow(new DuplicateKeyException("Duplicate entry"))
                .when(conversationMapper).insert(any(ConversationEntity.class));

        // when
        persistentChatMemory.save(conversationId, List.of(new UserMessage("hello")), "");

        // then - 幂等:DuplicateKeyException 被吞掉,messages 仍正常写入
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
        persistentChatMemory.save(conversationId, messages, "tool-trace-01");

        // then - tool response 的 content 是可读摘要,metadata 是结构化 JSON
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageMapper).insertBatch(captor.capture());
        List<MessageEntity> entities = captor.getValue();

        assertEquals(2, entities.size());
        assertEquals("user", entities.get(0).getRole());
        assertEquals("tool", entities.get(1).getRole());
        assertEquals("amapWeather(北京 晴 25°C)", entities.get(1).getContent());
    }

    // ============= load =============

    @Test
    void load_shouldHitCacheFirst() {
        // given - cache 命中
        String conversationId = "test-conv-load";
        List<CacheMessageDto> cachedDtos = List.of(
                CacheMessageDto.builder().role("user").content("hello").build()
        );
        when(messageCache.get(conversationId)).thenReturn(Optional.of(cachedDtos));

        // when
        List<Message> result = persistentChatMemory.load(conversationId);

        // then - cache hit, no DB query, no backfill
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(MessageType.USER, result.get(0).getMessageType());
        verify(messageMapper, never()).listByConversation(anyString());
        verify(messageCache, never()).put(anyString(), any(), any(Duration.class));
    }

    @Test
    void load_whenCacheMiss_shouldQueryDbAndBackfill() {
        // given - cache 未命中
        String conversationId = "test-conv-miss";
        when(messageCache.get(conversationId)).thenReturn(Optional.empty());

        MessageEntity userEntity = new MessageEntity();
        userEntity.setRole("user");
        userEntity.setContent("hello");
        when(messageMapper.listByConversation(conversationId)).thenReturn(List.of(userEntity));

        // when
        List<Message> result = persistentChatMemory.load(conversationId);

        // then
        assertNotNull(result);
        assertEquals(1, result.size());
        verify(messageMapper).listByConversation(conversationId);
        // 回填缓存
        verify(messageCache).put(eq(conversationId), any(), eq(Duration.ofSeconds(CACHE_TTL_SECONDS)));
    }

    @Test
    void load_whenRedisDown_shouldFallbackToDb() {
        // given - 缓存层已降级(返回 Optional.empty),RedisMessageCache 内部已捕获
        String conversationId = "test-conv-down";
        when(messageCache.get(conversationId)).thenReturn(Optional.empty());

        MessageEntity userEntity = new MessageEntity();
        userEntity.setRole("user");
        userEntity.setContent("hello");
        when(messageMapper.listByConversation(conversationId)).thenReturn(List.of(userEntity));

        // when - 应正常返回不抛异常
        List<Message> result = persistentChatMemory.load(conversationId);

        // then - 走 MySQL,回填由 messageCache.put 自带降级
        assertNotNull(result);
        assertEquals(1, result.size());
        verify(messageMapper).listByConversation(conversationId);
        verify(messageCache).put(eq(conversationId), any(), any(Duration.class));
    }

    @Test
    void load_whenCacheHitIsEmptyList_shouldNotReturnEmpty() {
        // given - 防御性:cache 命中但返回空列表(被历史异常写入污染),避免直接返回空
        String conversationId = "test-conv-empty";
        when(messageCache.get(conversationId)).thenReturn(Optional.of(List.of()));

        MessageEntity userEntity = new MessageEntity();
        userEntity.setRole("user");
        userEntity.setContent("hello");
        when(messageMapper.listByConversation(conversationId)).thenReturn(List.of(userEntity));

        // when
        List<Message> result = persistentChatMemory.load(conversationId);

        // then - 空缓存被忽略,走 MySQL
        assertNotNull(result);
        assertTrue(result.size() > 0);
        verify(messageMapper).listByConversation(conversationId);
    }

    // ============= clear =============

    @Test
    void clear_shouldDeleteMessagesAndSoftDeleteConversation() {
        // given
        String conversationId = "test-conv-clear";
        ConversationEntity conv = new ConversationEntity();
        conv.setId(conversationId);
        conv.setStatus("active");
        when(conversationMapper.selectById(conversationId)).thenReturn(conv);

        // when
        persistentChatMemory.clear(conversationId);

        // then
        verify(messageMapper).delete(any());
        verify(messageCache).clear(conversationId);
        verify(conversationMapper).updateById(argThat((ConversationEntity c) ->
                "deleted".equals(c.getStatus()) && c.getMessageCount() == 0));
    }
}