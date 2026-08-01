package uno.zhuchen.agent.persistence.cache;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * 会话消息保存事件。
 *
 * <p>由记忆控制层 {@code PersistentChatMemory.save()} 在事务内发布,
 * 由缓存层 {@link RedisMessageCache} 通过 {@code @TransactionalEventListener(AFTER_COMMIT)}
 * 监听并在事务真正提交后回填 Redis。
 *
 * <p>事件载荷是 {@link CacheMessageDto} 而非 Spring AI {@code Message},
 * 让缓存层完全不依赖上层领域类型。
 */
@Getter
public class SessionMessageSavedEvent extends ApplicationEvent {

    private final String conversationId;
    private final List<CacheMessageDto> dtos;

    public SessionMessageSavedEvent(Object source, String conversationId, List<CacheMessageDto> dtos) {
        super(source);
        this.conversationId = conversationId;
        this.dtos = dtos;
    }
}