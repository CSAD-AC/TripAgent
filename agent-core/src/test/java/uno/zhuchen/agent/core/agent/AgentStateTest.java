package uno.zhuchen.agent.core.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStateTest {

    @Test
    void systemContextIsMergedIntoSystemPrompt() {
        AgentState state = new AgentState("u1", "c1", "SYS", "你好");
        state.setSystemContext("[长期记忆]\n- 经历：规划过京都五日游");

        List<Message> full = state.getFullMessages();

        assertEquals(2, full.size());
        assertEquals("SYSTEM", full.get(0).getMessageType().name());
        assertTrue(full.get(0).getText().contains("SYS"));
        assertTrue(full.get(0).getText().contains("京都五日游"));
        assertEquals("USER", full.get(1).getMessageType().name());
        assertEquals("你好", full.get(1).getText());
    }

    /** 背景上下文只参与本次请求, 不能被落库。 */
    @Test
    void systemContextIsNotPersisted() {
        AgentState state = new AgentState("u1", "c1", "SYS", "你好");
        state.setSystemContext("[长期记忆]\n- 偏好：喜欢川菜");

        List<Message> newMessages = state.getNewMessages();

        assertEquals(1, newMessages.size());
        assertEquals("你好", newMessages.get(0).getText());
    }

    @Test
    void blankSystemContextIsSkipped() {
        AgentState state = new AgentState("u1", "c1", "SYS", "你好");
        state.setSystemContext("   ");

        assertEquals(2, state.getFullMessages().size());
    }
}
