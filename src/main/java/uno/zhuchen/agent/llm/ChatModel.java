package uno.zhuchen.agent.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * LLM 调用抽象 — 隔离具体模型实现
 *
 * <p>类比 MVC 中的 Mapper 层：定义数据访问契约（此处为 LLM 调用），
 * 具体实现在 impl/ 子包中切换。</p>
 */
public interface ChatModel {

    /**
     * 同步调用 LLM，返回模型回复
     *
     * @param messages 包含 system prompt 和消息历史的完整列表
     * @return 模型回复（可能含 toolCalls）
     */
    AssistantMessage call(List<Message> messages);
}
