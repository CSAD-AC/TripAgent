package uno.zhuchen.agent.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
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

    @Override
    public void save(String conversationId, List<Message> messages) {
        // 仅保留最新的 N 条（不含 system prompt）
        List<Message> trimmed = messages.stream()
                .skip(Math.max(0, messages.size() - MAX_MESSAGES_PER_SESSION))
                .collect(Collectors.toList());
        store.put(conversationId, trimmed);
    }

    @Override
    public List<Message> load(String conversationId) {
        List<Message> messages = store.get(conversationId);
        return messages != null ? messages : List.of();
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}
