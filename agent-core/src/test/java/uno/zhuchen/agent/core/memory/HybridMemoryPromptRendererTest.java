package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridMemoryPromptRendererTest {

    @Test
    void disabledRendererReturnsEmpty() {
        MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
        HybridMemoryPromptRenderer renderer =
                new HybridMemoryPromptRenderer(retrieval, false, 1000, 8);

        assertEquals("", renderer.render("default", "u1", null, "京都"));
    }

    @Test
    void rendererAddsBoundedSection() {
        MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
        when(retrieval.load(any(), any(), any(), any())).thenReturn(new MemoryPromptContext(
                List.of("偏好：美食"), List.of("经历：京都"), List.of()));
        HybridMemoryPromptRenderer renderer =
                new HybridMemoryPromptRenderer(retrieval, true, 1000, 8);

        String rendered = renderer.render("default", "u1", null, "京都");

        assertTrue(rendered.contains("[混合长期记忆]"));
        assertTrue(rendered.contains("偏好：美食"));
        assertTrue(rendered.contains("经历：京都"));
        assertTrue(rendered.contains("以本次要求为准"));
    }
}
