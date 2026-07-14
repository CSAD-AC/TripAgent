package uno.zhuchen.agent.persistence.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MyBatis-Plus + Redis 双写对话记忆实现。
 *
 * <p>写路径: save() -> 1. MySQL 写入（事务内）-> 2. 事务提交后异步更新 Redis 缓存
 * <p>读路径: load() -> 1. Redis 查缓存 -> 2. 未命中则 MySQL 查 -> 3. 回填 Redis
 *
 * <p>工具调用结构完整性:
 * AssistantMessage 的 tool_calls 和 ToolResponseMessage 的 responses 被完整保留
 * 在 message.metadata 列（MySQL）和 CacheMessageDto（Redis）中，
 * 以确保从历史加载的消息序列能被 DeepSeek / OpenAI API 正确识别。
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

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
        // 1. 确保 conversation 存在（幂等写入）
        ensureConversationExists(conversationId, messages);

        // 2. 查询当前最大序号作为起始偏移，防止续聊时 seq 冲突
        int seqOffset = messageMapper.selectMaxSequenceNum(conversationId) + 1;

        // 3. 批量写入 message 表
        List<MessageEntity> entities = toEntities(conversationId, messages, seqOffset);
        if (!entities.isEmpty()) {
            messageMapper.insertBatch(entities);
        }

        // 4. 原子自增消息计数（只计真正插入的新消息数）
        conversationMapper.incrementMessageCount(conversationId, entities.size());

        // 5. 发布事务提交后事件，异步更新 Redis 缓存
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
        messageMapper.delete(new QueryWrapper<MessageEntity>()
                .eq("conversation_id", conversationId));

        try {
            redisTemplate.delete(SESSION_CACHE_PREFIX + conversationId);
        } catch (Exception e) {
            log.warn("[cache] Redis unavailable, skip cache clear. conversationId={}", conversationId);
        }

        ConversationEntity conv = conversationMapper.selectById(conversationId);
        if (conv != null) {
            conv.setMessageCount(0);
            conv.setStatus("deleted");
            conversationMapper.updateById(conv);
        }
    }

    // ============ MessageEntity 序列化（MySQL） ============

    private List<MessageEntity> toEntities(String conversationId, List<Message> messages, int seqOffset) {
        List<MessageEntity> entities = new ArrayList<>();
        int seq = seqOffset;
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
                // 保存 tool_calls 到 metadata
                List<AssistantMessage.ToolCall> toolCalls = am.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    entity.setMetadata(toJson(toolCalls.stream()
                            .map(tc -> Map.of(
                                    "id", tc.id(),
                                    "type", tc.type(),
                                    "name", tc.name(),
                                    "arguments", tc.arguments()))
                            .toList()));
                }
            } else if (msg instanceof ToolResponseMessage trm) {
                entity.setRole("tool");
                // 把多个 tool response 存成 JSON 数组: [{id, name, responseData}, ...]
                List<Map<String, String>> responseList = trm.getResponses().stream()
                        .map(r -> Map.of(
                                "id", r.id(),
                                "name", r.name(),
                                "responseData", r.responseData()))
                        .toList();
                entity.setMetadata(toJson(responseList));
                // content 保留可读摘要（前端也可用）
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
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private List<Message> toMessages(List<MessageEntity> entities) {
        List<Message> messages = new ArrayList<>(entities.size());
        for (MessageEntity entity : entities) {
            Message msg = switch (entity.getRole()) {
                case "system" -> new SystemMessage(entity.getContent());
                case "user" -> new UserMessage(entity.getContent());
                case "assistant" -> buildAssistantMessage(entity);
                case "tool" -> buildToolResponseMessage(entity);
                default -> null;
            };
            if (msg != null) messages.add(msg);
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private AssistantMessage buildAssistantMessage(MessageEntity entity) {
        String text = entity.getContent() != null ? entity.getContent() : "";
        Map<String, Object> metadata = entity.getMetadata() != null
                ? fromJson(entity.getMetadata(), Map.class)
                : Collections.emptyMap();

        if (entity.getMetadata() != null) {
            try {
                List<Map<String, String>> toolCallMaps = objectMapper.readValue(
                        entity.getMetadata(),
                        new TypeReference<List<Map<String, String>>>() {});
                if (toolCallMaps != null && !toolCallMaps.isEmpty()) {
                    List<AssistantMessage.ToolCall> toolCalls = toolCallMaps.stream()
                            .map(m -> new AssistantMessage.ToolCall(
                                    m.get("id"),
                                    m.getOrDefault("type", "function"),
                                    m.get("name"),
                                    m.get("arguments")))
                            .toList();
                    // AssistantMessage 有公开的 Builder
                    return AssistantMessage.builder()
                            .content(text)
                            .properties(metadata)
                            .toolCalls(toolCalls)
                            .build();
                }
            } catch (Exception e) {
                log.warn("[load] Failed to parse assistant tool_calls metadata, text={}", text, e);
            }
        }
        return new AssistantMessage(text);
    }

    @SuppressWarnings("unchecked")
    private ToolResponseMessage buildToolResponseMessage(MessageEntity entity) {
        if (entity.getMetadata() != null) {
            try {
                List<Map<String, String>> responseMaps = objectMapper.readValue(
                        entity.getMetadata(),
                        new TypeReference<List<Map<String, String>>>() {});
                if (responseMaps != null && !responseMaps.isEmpty()) {
                    List<ToolResponseMessage.ToolResponse> responses = responseMaps.stream()
                            .map(m -> new ToolResponseMessage.ToolResponse(
                                    m.get("id"),
                                    m.get("name"),
                                    m.get("responseData")))
                            .toList();
                    return ToolResponseMessage.builder()
                            .responses(responses)
                            .build();
                }
            } catch (Exception e) {
                log.warn("[load] Failed to parse tool response metadata", e);
            }
        }
        // 降级：用 content 摘要构造（旧数据兼容）
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "hist", "history", entity.getContent())))
                .build();
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

    // ============ DTO 转换（Redis 缓存） ============

    private List<CacheMessageDto> toDtos(List<Message> messages) {
        List<CacheMessageDto> dtos = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            CacheMessageDto.CacheMessageDtoBuilder builder = CacheMessageDto.builder()
                    .metadata(msg.getMetadata());

            if (msg instanceof SystemMessage sm) {
                builder.role("system").content(sm.getText());
            } else if (msg instanceof UserMessage um) {
                builder.role("user").content(um.getText());
            } else if (msg instanceof AssistantMessage am) {
                builder.role("assistant").content(am.getText());
                List<AssistantMessage.ToolCall> toolCalls = am.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    builder.toolCalls(toolCalls.stream()
                            .map(tc -> CacheMessageDto.CacheToolCallDto.builder()
                                    .id(tc.id())
                                    .type(tc.type())
                                    .name(tc.name())
                                    .arguments(tc.arguments())
                                    .build())
                            .toList());
                }
            } else if (msg instanceof ToolResponseMessage trm) {
                builder.role("tool").content(summarizeToolResponses(trm.getResponses()));
                builder.toolResponses(trm.getResponses().stream()
                        .map(r -> CacheMessageDto.CacheToolResponseDto.builder()
                                .id(r.id())
                                .name(r.name())
                                .responseData(r.responseData())
                                .build())
                        .toList());
            } else {
                continue;
            }
            dtos.add(builder.build());
        }
        return dtos;
    }

    private List<Message> fromDtos(List<CacheMessageDto> dtos) {
        List<Message> messages = new ArrayList<>(dtos.size());
        for (CacheMessageDto dto : dtos) {
            Message msg = switch (dto.getRole()) {
                case "system" -> new SystemMessage(dto.getContent());
                case "user" -> new UserMessage(dto.getContent());
                case "assistant" -> {
                    if (dto.getToolCalls() != null && !dto.getToolCalls().isEmpty()) {
                        List<AssistantMessage.ToolCall> toolCalls = dto.getToolCalls().stream()
                                .map(tc -> new AssistantMessage.ToolCall(
                                        tc.getId(), tc.getType(), tc.getName(), tc.getArguments()))
                                .toList();
                        yield AssistantMessage.builder()
                                .content(dto.getContent())
                                .properties(dto.getMetadata() != null ? dto.getMetadata() : Collections.emptyMap())
                                .toolCalls(toolCalls)
                                .build();
                    }
                    yield new AssistantMessage(dto.getContent());
                }
                case "tool" -> {
                    if (dto.getToolResponses() != null && !dto.getToolResponses().isEmpty()) {
                        List<ToolResponseMessage.ToolResponse> responses = dto.getToolResponses().stream()
                                .map(r -> new ToolResponseMessage.ToolResponse(
                                        r.getId(), r.getName(), r.getResponseData()))
                                .toList();
                        yield ToolResponseMessage.builder().responses(responses).build();
                    }
                    yield ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    "hist", "history", dto.getContent())))
                            .build();
                }
                default -> null;
            };
            if (msg != null) messages.add(msg);
        }
        return messages;
    }

    // ============ JSON 工具方法 ============

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[json] Serialization failed", e);
            return "[]";
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("[json] Deserialization failed: {}", json, e);
            return null;
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
