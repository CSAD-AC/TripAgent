package uno.zhuchen.agent.core.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPromptRendererTest {

    private final MemoryPromptRenderer renderer = new MemoryPromptRenderer(10, 800);

    @Test
    void emptySummaryRendersNothing() {
        assertEquals("", renderer.render(new MemorySummary(List.of())));
    }

    @Test
    void summaryRendersAdvisorySection() {
        String rendered = renderer.render(new MemorySummary(List.of(
                new MemorySummary.MemoryLine("偏好：美食", "美食"),
                new MemorySummary.MemoryLine("常用出发城市", "上海")
        )));

        assertTrue(rendered.contains("[用户长期记忆]"));
        assertTrue(rendered.contains("若与本次明确要求冲突，以本次要求为准"));
        assertTrue(rendered.contains("偏好：美食"));
        assertTrue(rendered.contains("常用出发城市：上海"));
    }
}
