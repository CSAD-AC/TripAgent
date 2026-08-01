package uno.zhuchen.agent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uno.zhuchen.agent.domain.entity.ConversationEntity;
import uno.zhuchen.agent.domain.entity.MessageEntity;
import uno.zhuchen.agent.persistence.cache.CacheMessageDto;
import uno.zhuchen.agent.persistence.cache.RedisMessageCache;
import uno.zhuchen.agent.persistence.cache.SessionMessageSavedEvent;
import uno.zhuchen.agent.persistence.mapper.ConversationMapper;
import uno.zhuchen.agent.persistence.mapper.MessageMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus + Redis 协作版对话记忆实现(production profile)。
 *
 * <p>记忆控制层 — 编排两个 MyBatis Mapper 做持久化、编排 {@link RedisMessageCache} 做缓存,
 * 不直接依赖 Spring Data Redis。
 *
 * <p>职责:
 * <ul>
 *   <li>save: ensureConversation(幂等) -> 分配 seq 偏移 -> 批量 INSERT IGNORE 写 message ->
 *       自增 conversation.message_count -> 发 {@link SessionMessageSavedEvent} 触发事务提交后的缓存回填</li>
 *   <li>load: 优先 cache.get;未命中走 MySQL + fromEntities;非空回填到缓存</li>
 *   <li>clear: 物理删除 message + 软删除 conversation(status='deleted') + 清缓存</li>
 * </ul>
 *
 * <p>序列化逻辑集中在本类的 private static 方法中:
 * Message ↔ MessageEntity(用于 MySQL 持久化)、Message ↔ CacheMessageDto(用于 Redis 缓存)。
 * 没有抽到独立工具类 — 只有本类一个调用方,合并进来更直接。
 *
 * <p>Profile: 仅 {@code !dev && !test} 生效,与 {@code InMemoryChatMemory} 的
 * {@code dev || test} profile 互斥,确保 {@code ChatMemory} 类型在同一时刻只有一个候选 bean。
 */
@Slf4j
@Component
@Profile("!dev && !test")
public class PersistentChatMemory implements ChatMemory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final RedisMessageCache messageCache;
    private final ApplicationEventPublisher eventPublisher;
    private final long cacheTtlSeconds;

    public PersistentChatMemory(MessageMapper messageMapper,
                                 ConversationMapper conversationMapper,
                                 RedisMessageCache messageCache,
                                 ApplicationEventPublisher eventPublisher,
                                 @Value("${app.cache.session-ttl:1800}") long cacheTtlSeconds) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.messageCache = messageCache;
        this.eventPublisher = eventPublisher;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(String conversationId, List<Message> messages, String traceId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(messages, "messages");

        // 1. 确保 conversation 存在(幂等)
        ensureConversationExists(conversationId, messages);

        // 2. 查询当前最大序号作为起始偏移
        int seqOffset = messageMapper.selectMaxSequenceNum(conversationId) + 1;

        // 3. 批量写 message 表(INSERT IGNORE 幂等)
        List<MessageEntity> entities = toEntities(conversationId, messages, seqOffset, traceId);
        if (!entities.isEmpty()) {
            messageMapper.insertBatch(entities);
        }

        // 4. 原子自增消息计数
        conversationMapper.incrementMessageCount(conversationId, entities.size());

        // 5. 发事务提交后事件,由 RedisMessageCache 监听器回填缓存
        //    载荷用 DTO 而非 Message,让缓存层不依赖 Spring AI 类型
        eventPublisher.publishEvent(
                new SessionMessageSavedEvent(this, conversationId, toDtos(messages)));
    }

    @Override
    public List<Message> load(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");

        // 1. 缓存优先
        var cached = messageCache.get(conversationId);
        if (cached.isPresent()) {
            List<Message> messages = fromDtos(cached.get());
            if (!messages.isEmpty()) {
                return messages;
            }
            // 防御性:空列表视为未命中
        }

        // 2. 缓存未命中/Redis 不可用 -> 查 MySQL
        List<MessageEntity> entities = messageMapper.listByConversation(conversationId);
        List<Message> messages = fromEntities(entities);

        // 3. 非空回填缓存(RedisMessageCache.put 内部已降级)
        if (!messages.isEmpty()) {
            messageCache.put(conversationId, toDtos(messages), Duration.ofSeconds(cacheTtlSeconds));
        }

        return messages;
    }

    @Override
    public void clear(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");

        // 物理删除消息
        messageMapper.delete(new QueryWrapper<MessageEntity>()
                .eq("conversation_id", conversationId));

        // 清缓存
        messageCache.clear(conversationId);

        // 软删除会话(ConversationEntity.updatedAt 由 MyMetaObjectHandler 自动填充)
        ConversationEntity conv = conversationMapper.selectById(conversationId);
        if (conv != null) {
            conv.setStatus("deleted");
            conv.setMessageCount(0);
            conversationMapper.updateById(conv);
        }
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

    // ============ 序列化:Message ↔ MessageEntity(MySQL 持久化方向) ============

    private static List<MessageEntity> toEntities(String conversationId, List<Message> messages,
                                                   int seqOffset, String traceId) {
        List<MessageEntity> entities = new ArrayList<>();
        int seq = seqOffset;
        LocalDateTime now = LocalDateTime.now();
        for (Message msg : messages) {
            MessageEntity entity = new MessageEntity();
            entity.setConversationId(conversationId);
            entity.setSequenceNum(seq++);
            entity.setTraceId(traceId);
            // 自定义 @Insert 不触发 MyMetaObjectHandler 自动填充,必须显式赋值(P0 修复)
            entity.setCreatedAt(now);

            if (msg instanceof SystemMessage sm) {
                entity.setRole("system");
                entity.setContent(sm.getText());
            } else if (msg instanceof UserMessage um) {
                entity.setRole("user");
                entity.setContent(um.getText());
            } else if (msg instanceof AssistantMessage am) {
                entity.setRole("assistant");
                entity.setContent(am.getText());
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
                List<Map<String, String>> responseList = trm.getResponses().stream()
                        .map(r -> Map.of(
                                "id", r.id(),
                                "name", r.name(),
                                "responseData", r.responseData()))
                        .toList();
                entity.setMetadata(toJson(responseList));
                entity.setContent(summarizeToolResponses(trm.getResponses()));
            } else {
                log.debug("Unknown message type, skip: {}", msg.getClass().getSimpleName());
                continue;
            }
            entities.add(entity);
        }
        return entities;
    }

    private static List<Message> fromEntities(List<MessageEntity> entities) {
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
    private static AssistantMessage buildAssistantMessage(MessageEntity entity) {
        String text = entity.getContent() != null ? entity.getContent() : "";
        Map<String, Object> metadata = entity.getMetadata() != null
                ? fromJson(entity.getMetadata(), Map.class)
                : Collections.emptyMap();

        if (entity.getMetadata() != null) {
            try {
                List<Map<String, String>> toolCallMaps = OBJECT_MAPPER.readValue(
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
    private static ToolResponseMessage buildToolResponseMessage(MessageEntity entity) {
        if (entity.getMetadata() != null) {
            try {
                List<Map<String, String>> responseMaps = OBJECT_MAPPER.readValue(
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
        // 降级:老数据用 content 摘要构造
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "hist", "history", entity.getContent())))
                .build();
    }

    private static String summarizeToolResponses(List<ToolResponseMessage.ToolResponse> responses) {
        return responses.stream()
                .map(r -> r.name() + "(" + truncate(r.responseData(), 60) + ")")
                .collect(Collectors.joining("; "));
    }

    // ============ 序列化:Message ↔ CacheMessageDto(Redis 缓存方向) ============

    private static List<CacheMessageDto> toDtos(List<Message> messages) {
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

    private static List<Message> fromDtos(List<CacheMessageDto> dtos) {
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

    // ============ JSON 工具 ============

    private static String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[json] Serialization failed", e);
            return "[]";
        }
    }

    private static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.error("[json] Deserialization failed: {}", json, e);
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}