package uno.zhuchen.agent.core.memory;

import org.springframework.ai.chat.messages.Message;
import uno.zhuchen.agent.common.exception.NotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存级对话记忆实现（开发/演示用）
 *
 * 消息按 conversationId 分组存储，仅保留最新 N 条以避免上下文过长。
 * 生产环境应替换为 Redis / MySQL 持久化实现。
 */
public class InMemoryChatMemory implements ChatMemory {

    /** 单会话保留的最大消息数 */
    private static final int MAX_MESSAGES_PER_SESSION = 20;

    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();
    private final Map<String, String> owners = new ConcurrentHashMap<>();

    @Override
    public void claim(String userId, String conversationId, String mode, String model) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(conversationId, "conversationId");
        String owner = owners.putIfAbsent(conversationId, userId);
        if (owner != null && !owner.equals(userId)) {
            throw new NotFoundException("对话不存在");
        }
    }

    @Override
    public void assertOwned(String userId, String conversationId) {
        String owner = owners.get(conversationId);
        if (owner == null || !owner.equals(userId)) {
            throw new NotFoundException("对话不存在");
        }
    }

    @Override
    public void save(String userId, String conversationId, List<Message> messages, String traceId,
                     String model, String mode) {
        claim(userId, conversationId, mode, model);
        List<Message> combined = new ArrayList<>(store.getOrDefault(conversationId, List.of()));
        combined.addAll(messages);
        List<Message> trimmed = combined.stream()
                .skip(Math.max(0, combined.size() - MAX_MESSAGES_PER_SESSION))
                .collect(Collectors.toList());
        store.put(conversationId, trimmed);
    }

    @Override
    public List<Message> load(String userId, String conversationId) {
        String owner = owners.get(conversationId);
        if (owner == null) {
            return List.of();
        }
        if (!owner.equals(userId)) {
            throw new NotFoundException("对话不存在");
        }
        List<Message> messages = store.get(conversationId);
        return messages != null ? messages : List.of();
    }

    @Override
    public void clear(String userId, String conversationId) {
        String owner = owners.get(conversationId);
        if (owner != null && !owner.equals(userId)) {
            throw new NotFoundException("对话不存在");
        }
        store.remove(conversationId);
        owners.remove(conversationId);
    }
}
