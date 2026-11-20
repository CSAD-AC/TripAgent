package uno.zhuchen.agent.core.memory.block;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.llm.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryBlockUpdaterTest {

    @Test
    void bestEffortPreservesEditedGeneratedContentWhenItFits() {
        ModelRouter router = mock(ModelRouter.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(router.route(null)).thenReturn(chatModel);
        when(router.resolveModelName(null)).thenReturn("test-model");
        when(chatModel.call(anyList(), eq("test-model"))).thenReturn(AssistantMessage.builder()
                .content("{\"profile\":\"用户编辑：不吃辣；不吃香菜\",\"events\":\"\"}")
                .build());

        MemoryBlockUpdater updater = new MemoryBlockUpdater(router, new ObjectMapper());
        MemoryBlockUpdateResult result = updater.update(
                "用户编辑：不吃辣",
                "",
                new MemoryCandidateResult(List.of("不吃香菜"), List.of(), List.of()),
                true,
                "KEYWORD");

        assertTrue(result.profile().contains("用户编辑：不吃辣"));
        assertTrue(result.profile().contains("不吃香菜"));
        assertTrue(result.generatedEditPreserved());
    }

    @Test
    void preservesEditedContentBeforeApplyingOversizedModelOutput() {
        ModelRouter router = mock(ModelRouter.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(router.route(null)).thenReturn(chatModel);
        when(router.resolveModelName(null)).thenReturn("test-model");
        when(chatModel.call(anyList(), eq("test-model"))).thenReturn(AssistantMessage.builder()
                .content("{\"profile\":\"用户编辑：" + "旧".repeat(100)
                        + "；" + "新".repeat(400) + "\",\"events\":\"\"}")
                .build());

        MemoryBlockUpdater updater = new MemoryBlockUpdater(router, new ObjectMapper());
        MemoryBlockUpdateResult result = updater.update(
                "用户编辑：" + "旧".repeat(100),
                "",
                new MemoryCandidateResult(List.of("新偏好"), List.of(), List.of()),
                true,
                "KEYWORD");

        assertTrue(result.generatedEditPreserved());
        assertTrue(result.profile().contains("用户编辑：" + "旧".repeat(100)));
        assertTrue(result.profile().contains("新"));
    }

    @Test
    void modelRewritesContradictoryEditedProfileWithLatestCandidate() {
        ModelRouter router = mock(ModelRouter.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(router.route(null)).thenReturn(chatModel);
        when(router.resolveModelName(null)).thenReturn("test-model");
        when(chatModel.call(anyList(), eq("test-model"))).thenReturn(AssistantMessage.builder()
                .content("""
                        {"profile":"喜欢吃辣；常住杭州","events":""}
                        """)
                .build());

        MemoryBlockUpdater updater = new MemoryBlockUpdater(router, new ObjectMapper());
        MemoryBlockUpdateResult result = updater.update(
                "用户编辑：不吃辣；常住杭州",
                "",
                new MemoryCandidateResult(List.of("喜欢吃辣"), List.of(), List.of()),
                true,
                "KEYWORD");

        assertTrue(result.profile().contains("喜欢吃辣"));
        assertFalse(result.profile().contains("不吃辣"));
        assertTrue(result.profile().contains("常住杭州"));
    }

    @Test
    void candidateIsAppliedEvenWhenModelOmitsIt() {
        ModelRouter router = mock(ModelRouter.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(router.route(null)).thenReturn(chatModel);
        when(router.resolveModelName(null)).thenReturn("test-model");
        when(chatModel.call(anyList(), eq("test-model"))).thenReturn(AssistantMessage.builder()
                .content("{\"profile\":\"常住杭州\",\"events\":\"\"}")
                .build());

        MemoryBlockUpdater updater = new MemoryBlockUpdater(router, new ObjectMapper());
        MemoryBlockUpdateResult result = updater.update(
                "常住杭州",
                "",
                new MemoryCandidateResult(List.of("通常两个人出行"), List.of(), List.of()),
                false,
                "KEYWORD");

        assertTrue(result.profile().contains("常住杭州"));
        assertTrue(result.profile().contains("通常两个人出行"));
    }

    @Test
    void editedOldProfileIsNotPrependedBeforeModelRewrite() {
        ModelRouter router = mock(ModelRouter.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(router.route(null)).thenReturn(chatModel);
        when(router.resolveModelName(null)).thenReturn("test-model");
        when(chatModel.call(anyList(), eq("test-model"))).thenReturn(AssistantMessage.builder()
                .content("""
                        {"profile":"常住杭州；通常一个人出行；预算5000元","events":""}
                        """)
                .build());

        MemoryBlockUpdater updater = new MemoryBlockUpdater(router, new ObjectMapper());
        MemoryBlockUpdateResult result = updater.update(
                "常住杭州；通常两个人出行；预算5000元",
                "",
                new MemoryCandidateResult(List.of("通常一个人出行"), List.of(), List.of()),
                true,
                "KEYWORD");

        assertTrue(result.profile().contains("通常一个人出行"));
        assertFalse(result.profile().contains("通常两个人出行"));
        assertTrue(result.profile().contains("常住杭州"));
        assertTrue(result.profile().contains("预算5000元"));
    }
}
