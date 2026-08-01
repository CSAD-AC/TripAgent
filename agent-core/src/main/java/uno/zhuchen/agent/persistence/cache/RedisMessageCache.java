package uno.zhuchen.agent.persistence.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 对话消息 Redis 缓存服务。
 *
 * <p>职责单一:管理 Redis 中会话消息列表的 get/put/clear 与 TTL。
 *
 * <p>协作关系:
 * <ul>
 *   <li>save 路径 — 监听 {@link SessionMessageSavedEvent} 的 {@code AFTER_COMMIT},
 *       在事务真正提交后才回填缓存,避免脏数据</li>
 *   <li>load 路径 — 由 {@code PersistentChatMemory} 主动调 get(),未命中调 put() 回填</li>
 *   <li>clear 路径 — 由 {@code PersistentChatMemory} 主动调 clear() 失效缓存</li>
 * </ul>
 *
 * <p>容错策略:Redis 不可用时所有调用均降级(warn log),不影响主流程,缓存不是真理之源。
 */
@Slf4j
@Component
public class RedisMessageCache {

    public static final String SESSION_CACHE_PREFIX = "session:";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final StringRedisTemplate redisTemplate;
    private final long defaultTtlSeconds;

    public RedisMessageCache(StringRedisTemplate redisTemplate,
                             @Value("${app.cache.session-ttl:1800}") long defaultTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public Optional<List<CacheMessageDto>> get(String conversationId) {
        String cacheKey = SESSION_CACHE_PREFIX + conversationId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return Optional.of(decodeList(cached));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[cache] Redis unavailable on GET, treat as miss. conversationId={}", conversationId, e);
            return Optional.empty();
        }
    }

    public void put(String conversationId, List<CacheMessageDto> dtos, Duration ttl) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        String cacheKey = SESSION_CACHE_PREFIX + conversationId;
        try {
            redisTemplate.opsForValue().set(cacheKey, encode(dtos), ttl);
        } catch (Exception e) {
            log.warn("[cache] Redis unavailable on PUT, skip. conversationId={}", conversationId, e);
        }
    }

    public void clear(String conversationId) {
        try {
            redisTemplate.delete(SESSION_CACHE_PREFIX + conversationId);
        } catch (Exception e) {
            log.warn("[cache] Redis unavailable on CLEAR, skip. conversationId={}", conversationId, e);
        }
    }

    /**
     * 事务提交后异步回填缓存(由 save 路径触发)。
     *
     * <p>{@link TransactionPhase#AFTER_COMMIT} 保证只有事务真正提交后才会执行,
     * 避免缓存写先于数据库的脏数据问题。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSessionSaved(SessionMessageSavedEvent event) {
        put(event.getConversationId(), event.getDtos(), Duration.ofSeconds(defaultTtlSeconds));
    }

    // ============ 内部 JSON 工具 ============

    private String encode(List<CacheMessageDto> dtos) {
        try {
            return OBJECT_MAPPER.writeValueAsString(dtos);
        } catch (Exception e) {
            log.error("[cache] JSON encode failed", e);
            return "[]";
        }
    }

    private List<CacheMessageDto> decodeList(String json) {
        try {
            return OBJECT_MAPPER.readValue(json,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, CacheMessageDto.class));
        } catch (Exception e) {
            log.error("[cache] JSON decode failed", e);
            return List.of();
        }
    }
}