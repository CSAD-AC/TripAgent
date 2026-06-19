package uno.zhuchen.agent.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 对话记忆存取 — 类比 Mapper 层，抽象持久化方式
 *
 * 后续可扩展为 Redis 实现、MySQL 实现等。
 */
public interface ChatMemory {

    /**
     * 保存指定会话的消息列表
     */
    void save(String conversationId, List<Message> messages);

    /**
     * 加载指定会话的消息历史
     */
    List<Message> load(String conversationId);

    /**
     * 清除指定会话的记忆
     */
    void clear(String conversationId);
}
