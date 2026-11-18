package uno.zhuchen.agent.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.llm.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmMemoryCandidateExtractorTest {

    @Mock
    private ModelRouter modelRouter;

    @Mock
    private ChatModel chatModel;

    private LlmMemoryCandidateExtractor extractor;

    @BeforeEach
    void setUp() {
        RuleMemoryCandidateExtractor rule =
                new RuleMemoryCandidateExtractor(new MemoryExtractor(), new ObjectMapper());
        extractor = new LlmMemoryCandidateExtractor(
                modelRouter, new ObjectMapper(), rule, "", 5, 40);
    }

    @Test
    void parsesStructuredMemoriesFromModelOutput() {
        when(modelRouter.route(any())).thenReturn(chatModel);
        when(modelRouter.resolveModelName(any())).thenReturn("global:deepseek-v4.1-flash");
        when(chatModel.call(anyList(), (String) any())).thenReturn(new AssistantMessage("""
                ```json
                {"memories":[
                  {"type":"CONSTRAINT","key":"travel.constraint.hotel","value":"同行有老人, 需要一楼房间",
                   "confidence":0.92,"importance":0.8},
                  {"type":"UNKNOWN","key":"x","value":"y","confidence":0.5,"importance":0.5}
                ]}
                ```
                """));

        List<MemoryExtractionResult> results = extractor.extract("{\"message\":\"这次带老人出行\"}");

        assertEquals(1, results.size(), "非法类型必须被丢弃");
        assertEquals(MemoryItemType.CONSTRAINT, results.get(0).type());
        assertEquals("travel.constraint.hotel", results.get(0).key());
        assertEquals("llm-v1", extractor.version());
    }

    @Test
    void fallsBackToRuleWhenJsonInvalid() {
        when(modelRouter.route(any())).thenReturn(chatModel);
        when(modelRouter.resolveModelName(any())).thenReturn("global:deepseek-v4.1-flash");
        when(chatModel.call(anyList(), (String) any())).thenReturn(new AssistantMessage("我无法回答"));

        List<MemoryExtractionResult> results = extractor.extract("{\"message\":\"我喜欢美食\"}");

        assertEquals(1, results.size());
        assertEquals("travel.preference.food", results.get(0).key());
    }

    @Test
    void fallsBackToRuleWhenModelThrows() {
        when(modelRouter.route(any())).thenThrow(new IllegalStateException("网关超时"));

        List<MemoryExtractionResult> results = extractor.extract("{\"message\":\"我喜欢美食\"}");

        assertEquals(1, results.size());
        assertEquals("travel.preference.food", results.get(0).key());
    }

    @Test
    void sensitiveMessageIsRejectedBeforeCallingModel() {
        assertTrue(extractor.extract("{\"message\":\"我的身份证号是 110101\"}").isEmpty());
    }
}
