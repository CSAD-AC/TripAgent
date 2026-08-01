package uno.zhuchen.agent.core.memory;

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
     *
     * @param conversationId 会话 ID
     * @param messages       消息列表
     * @param traceId        链路追踪 ID, 落库到 message.trace_id 列支撑溯源;
     *                       无链路追踪上下文时可传空字符串
     */
    void save(String conversationId, List<Message> messages, String traceId);

    /**
     * 保存指定会话的消息列表(无链路追踪上下文的便捷入口)
     */
    default void save(String conversationId, List<Message> messages) {
        save(conversationId, messages, "");
    }

    /**
     * 加载指定会话的消息历史
     */
    List<Message> load(String conversationId);

    /**
     * 清除指定会话的记忆
     */
    void clear(String conversationId);
}
