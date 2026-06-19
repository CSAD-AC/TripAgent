package uno.zhuchen.agent.agent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agent 状态 — 管理 ReAct 循环中每一轮的状态
 *
 * <p>职责：维护消息历史、迭代计数、运行时上下文。
 * 不持有业务逻辑，仅作为状态容器。</p>
 */
public class AgentState {

    private final String conversationId;
    private final SystemMessage systemPrompt;
    private final List<Message> messages;
    private int iterationCount;

    public AgentState(String conversationId, String systemPromptText, String userInput) {
        this.conversationId = conversationId != null ? conversationId : UUID.randomUUID().toString().substring(0, 8);
        this.systemPrompt = new SystemMessage(systemPromptText);
        this.messages = new ArrayList<>();
        this.messages.add(new UserMessage(userInput));
        this.iterationCount = 0;
    }

    /**
     * 追加一轮推理结果到消息历史
     */
    public void addReasoningResult(Message assistantMessage) {
        this.messages.add(assistantMessage);
        this.iterationCount++;
    }

    /**
     * 获取送给 LLM 的完整消息列表（system prompt + 历史）
     */
    public List<Message> getFullMessages() {
        List<Message> full = new ArrayList<>();
        full.add(systemPrompt);
        full.addAll(messages);
        return full;
    }

    // --- getters ---

    public String getConversationId() {
        return conversationId;
    }

    public SystemMessage getSystemPrompt() {
        return systemPrompt;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public int getIterationCount() {
        return iterationCount;
    }
}
