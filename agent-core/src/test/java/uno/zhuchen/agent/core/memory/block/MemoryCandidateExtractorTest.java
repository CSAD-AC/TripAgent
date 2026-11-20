package uno.zhuchen.agent.core.memory.block;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.llm.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryCandidateExtractorTest {

    @Test
    void extractsGlobalFactAndDiscardsTripScopedFact() {
        ModelRouter router = mock(ModelRouter.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(router.route(null)).thenReturn(chatModel);
        when(router.resolveModelName(null)).thenReturn("test-model");
        when(chatModel.call(anyList(), eq("test-model"))).thenReturn(AssistantMessage.builder()
                .content("""
                        {
                          "profileCandidates": ["不吃辣"],
                          "eventCandidates": [],
                          "discarded": ["这次带老人出行"]
                        }
                        """)
                .build());

        MemoryCandidateExtractor extractor =
                new MemoryCandidateExtractor(router, new ObjectMapper());
        MemoryCandidateResult result = extractor.extract(
                List.of("记住：我不吃辣，这次带老人出行"));

        assertEquals(List.of("不吃辣"), result.profileCandidates());
        assertTrue(result.eventCandidates().isEmpty());
        assertTrue(result.discarded().contains("这次带老人出行"));
    }
}
