package uno.zhuchen.agent.persistence.cache;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uno.zhuchen.agent.domain.entity.ConversationEntity;
import uno.zhuchen.agent.domain.entity.MessageEntity;
import uno.zhuchen.agent.persistence.mapper.ConversationMapper;
import uno.zhuchen.agent.persistence.mapper.MessageMapper;
import uno.zhuchen.agent.core.memory.ChatMemory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus + Redis 双写对话记忆实现。
 *
 * 写路径: save() -> 1. MySQL 写入（事务内）-> 2. 事务提交后异步更新 Redis 缓存
 * 读路径: load() -> 1. Redis 查缓存 -> 2. 未命中则 MySQL 查 -> 3. 回填 Redis
 *
 * 默认 profile 下启用（deepseek/dashscope）; dev/test profile 用 InMemoryChatMemory。
 */
@Slf4j
@Component
@Profile("!dev & !test")
public class DbChatMemory implements ChatMemory {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.cache.session-ttl:1800}")
    private long sessionCacheTtlSeconds;

    private static final String SESSION_CACHE_PREFIX = "session:";

    public DbChatMemory(ConversationMapper conversationMapper,
                        MessageMapper messageMapper,
                        StringRedisTemplate redisTemplate,
                        ApplicationEventPublisher eventPublisher) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(String conversationId, List<Message> messages) {
        // 1. 确保 conversation 存在（幂等写入，防并发竞态）
        ensureConversationExists(conversationId, messages);

        // 2. 批量写入 message 表
        List<MessageEntity> entities = toEntities(conversationId, messages);
        if (!entities.isEmpty()) {
            messageMapper.insertBatch(entities);
        }

        // 3. 原子自增消息计数
        conversationMapper.incrementMessageCount(conversationId, entities.size());

        // 4. 发布事务提交后事件，异步更新 Redis 缓存
        eventPublisher.publishEvent(new ChatSaveEvent(this, conversationId, messages));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleChatSaveEvent(ChatSaveEvent event) {
        try {
            String cacheKey = SESSION_CACHE_PREFIX + event.getConversationId();
            List<CacheMessageDto> dtos = toDtos(event.getMessages());
            redisTemplate.opsForValue().set(
                    cacheKey, toJson(dtos),
                    Duration.ofSeconds(sessionCacheTtlSeconds));
        } catch (Exception e) {
            log.warn("[cache] Failed to update session cache, conversationId={}", event.getConversationId(), e);
        }
    }

    @Getter
    public static class ChatSaveEvent extends ApplicationEvent {
        private final String conversationId;
        private final List<Message> messages;

        public ChatSaveEvent(Object source, String conversationId, List<Message> messages) {
            super(source);
            this.conversationId = conversationId;
            this.messages = messages;
        }
    }

    @Override
    public List<Message> load(String conversationId) {
        String cacheKey = SESSION_CACHE_PREFIX + conversationId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                List<CacheMessageDto> dtos = fromJsonList(cached);
                return fromDtos(dtos);
            }
        } catch (Exception e) {
            log.warn("[cache] Redis unavailable, fallback to MySQL. conversationId={}", conversationId);
        }

        // 缓存未命中 -> 查 MySQL
        List<MessageEntity> entities = messageMapper.listByConversation(conversationId);
        List<Message> messages = toMessages(entities);

        // 回填缓存
        if (!messages.isEmpty()) {
            try {
                List<CacheMessageDto> dtos = toDtos(messages);
                redisTemplate.opsForValue().set(
                        cacheKey, toJson(dtos),
                        Duration.ofSeconds(sessionCacheTtlSeconds));
            } catch (Exception e) {
                log.warn("[cache] Redis unavailable, skip cache backfill. conversationId={}", conversationId);
            }
        }

        return messages;
    }

    @Override
    public void clear(String conversationId) {
        // 物理删除消息
        messageMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MessageEntity>()
                        .eq("conversation_id", conversationId));

        // 清除 Redis 缓存
        try {
            redisTemplate.delete(SESSION_CACHE_PREFIX + conversationId);
        } catch (Exception e) {
            log.warn("[cache] Redis unavailable, skip cache clear. conversationId={}", conversationId);
        }

        // 软删除对话
        ConversationEntity conv = conversationMapper.selectById(conversationId);
        if (conv != null) {
            conv.setMessageCount(0);
            conv.setStatus("deleted");
            conversationMapper.updateById(conv);
        }
    }

    // ============ 序列化 / 反序列化 ============

    private List<MessageEntity> toEntities(String conversationId, List<Message> messages) {
        List<MessageEntity> entities = new ArrayList<>();
        int seq = 0;
        for (Message msg : messages) {
            MessageEntity entity = new MessageEntity();
            entity.setConversationId(conversationId);
            entity.setSequenceNum(seq++);
            entity.setTraceId(extractTraceId(msg));

            if (msg instanceof SystemMessage sm) {
                entity.setRole("system");
                entity.setContent(sm.getText());
            } else if (msg instanceof UserMessage um) {
                entity.setRole("user");
                entity.setContent(um.getText());
            } else if (msg instanceof AssistantMessage am) {
                entity.setRole("assistant");
                entity.setContent(am.getText());
            } else if (msg instanceof ToolResponseMessage trm) {
                entity.setRole("tool");
                entity.setContent(summarizeToolResponses(trm.getResponses()));
            } else {
                log.debug("Unknown message type: {}, skip", msg.getClass().getSimpleName());
                continue;
            }
            entities.add(entity);
        }
        return entities;
    }

    private String extractTraceId(Message msg) {
        Object traceId = msg.getMetadata().get("trace_id");
        return traceId != null ? traceId.toString() : null;
    }

    private String summarizeToolResponses(List<ToolResponseMessage.ToolResponse> responses) {
        return responses.stream()
                .map(r -> r.name() + "(" + truncate(r.responseData(), 60) + ")")
                .collect(Collectors.joining("; "));
    }

    private List<Message> toMessages(List<MessageEntity> entities) {
        List<Message> messages = new ArrayList<>(entities.size());
        for (MessageEntity entity : entities) {
            Message msg = switch (entity.getRole()) {
                case "system" -> new SystemMessage(entity.getContent());
                case "user" -> new UserMessage(entity.getContent());
                case "assistant" ->
                        new AssistantMessage(entity.getContent());
                case "tool" ->
                        ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                        "hist", "history", entity.getContent())))
                                .build();
                default -> null;
            };
            if (msg != null) messages.add(msg);
        }
        return messages;
    }

    // ============ 会话管理 ============

    private void ensureConversationExists(String conversationId, List<Message> messages) {
        ConversationEntity conv = new ConversationEntity();
        conv.setId(conversationId);
        messages.stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst()
                .ifPresent(m -> {
                    String text = m.getText();
                    conv.setTitle(text.length() > 100 ? text.substring(0, 100) + "..." : text);
                    conv.setFirstMessage(text.length() > 100 ? text.substring(0, 100) : text);
                });
        try {
            conversationMapper.insert(conv);
        } catch (DuplicateKeyException e) {
            log.debug("Conversation already exists, skip insert. id={}", conversationId);
        }
    }

    // ============ DTO 转换（避免序列化 Spring AI 内部类型） ============

    private List<CacheMessageDto> toDtos(List<Message> messages) {
        List<CacheMessageDto> dtos = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            String role;
            if (msg instanceof SystemMessage) {
                role = "system";
            } else if (msg instanceof UserMessage) {
                role = "user";
            } else if (msg instanceof AssistantMessage) {
                role = "assistant";
            } else if (msg instanceof ToolResponseMessage) {
                role = "tool";
            } else {
                continue;
            }

            String content;
            if (msg instanceof AssistantMessage am) {
                content = am.getText();
            } else if (msg instanceof ToolResponseMessage trm) {
                content = summarizeToolResponses(trm.getResponses());
            } else {
                content = msg.getText();
            }
            dtos.add(new CacheMessageDto(role, content, msg.getMetadata()));
        }
        return dtos;
    }

    private List<Message> fromDtos(List<CacheMessageDto> dtos) {
        List<Message> messages = new ArrayList<>(dtos.size());
        for (CacheMessageDto dto : dtos) {
            Message msg = switch (dto.getRole()) {
                case "system" -> new SystemMessage(dto.getContent());
                case "user" -> new UserMessage(dto.getContent());
                case "assistant" ->
                        new AssistantMessage(dto.getContent());
                case "tool" ->
                        ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                        "hist", "history", dto.getContent())))
                                .build();
                default -> null;
            };
            if (msg != null) messages.add(msg);
        }
        return messages;
    }

    // ============ JSON 工具方法 ============

    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[json] Serialization failed", e);
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<CacheMessageDto> fromJsonList(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, CacheMessageDto.class));
        } catch (Exception e) {
            log.error("[json] Deserialization failed", e);
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
