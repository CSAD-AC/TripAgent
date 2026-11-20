package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import uno.zhuchen.agent.common.exception.NotFoundException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryChatMemoryIsolationTest {

    @Test
    void oneUserCannotReadOrAppendAnotherUsersConversation() {
        InMemoryChatMemory memory = new InMemoryChatMemory();
        memory.save("user-a", "c1", List.of(new UserMessage("hello")), "trace");

        assertEquals(1, memory.load("user-a", "c1").size());
        assertThrows(NotFoundException.class, () -> memory.load("user-b", "c1"));
        assertThrows(NotFoundException.class, () -> memory.save(
                "user-b", "c1", List.of(new UserMessage("attack")), "trace"));
    }

    @Test
    void saveAppendsMessagesAcrossTurns() {
        InMemoryChatMemory memory = new InMemoryChatMemory();
        memory.save("user-a", "c1", List.of(new UserMessage("first")), "trace");
        memory.save("user-a", "c1", List.of(new UserMessage("second")), "trace");

        assertEquals(2, memory.load("user-a", "c1").size());
    }
}
