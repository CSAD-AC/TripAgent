package uno.zhuchen.agent.core.memory.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LongTermMemoryContextBuilderTest {

    @Test
    void rendersUserPromptAndAutoBlocks() {
        MemoryBlockService blockService = mock(MemoryBlockService.class);
        when(blockService.userPrompt("u1")).thenReturn("回复保持简洁，不要 emoji");
        when(blockService.content("u1", MemoryBlockType.PROFILE)).thenReturn("用户叫张三，性别男");
        when(blockService.content("u1", MemoryBlockType.EVENT)).thenReturn("2025-11-07 去北京玩了三天");
        LongTermMemoryContextBuilder builder = new LongTermMemoryContextBuilder(blockService);

        String text = builder.render("u1");

        assertTrue(text.contains("[长期记忆]"));
        assertTrue(text.contains("[用户固定提示词｜最高优先级且模型不可修改]"));
        assertTrue(text.contains("回复保持简洁"));
        assertTrue(text.contains("[自动记忆｜模型可更新，用户可编辑]"));
        assertTrue(text.contains("用户信息"));
        assertTrue(text.contains("事件记忆"));
    }

    @Test
    void returnsNullWhenMemoryIsEmpty() {
        MemoryBlockService blockService = mock(MemoryBlockService.class);
        when(blockService.userPrompt("u1")).thenReturn("");
        when(blockService.content("u1", MemoryBlockType.PROFILE)).thenReturn("");
        when(blockService.content("u1", MemoryBlockType.EVENT)).thenReturn("");
        LongTermMemoryContextBuilder builder = new LongTermMemoryContextBuilder(blockService);

        org.junit.jupiter.api.Assertions.assertNull(builder.render("u1"));
    }
}
