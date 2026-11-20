package uno.zhuchen.agent.core.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MemoryPromptRenderer {

    private final int maxItems;
    private final int maxChars;

    public MemoryPromptRenderer(
            @Value("${app.memory.long-term.max-items:10}") int maxItems,
            @Value("${app.memory.long-term.max-chars:800}") int maxChars) {
        this.maxItems = Math.max(1, maxItems);
        this.maxChars = Math.max(100, maxChars);
    }

    public String render(MemorySummary summary) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("[用户长期记忆]\n")
                .append("以下是用户过去明确表达的偏好，仅作为参考；若与本次明确要求冲突，以本次要求为准：\n");
        int count = 0;
        for (MemorySummary.MemoryLine line : summary.lines()) {
            String label = safeLabel(line.label());
            String value = safeValue(line.value());
            String rendered = "- " + label + (label.endsWith("：") ? "" : "：") + value + "\n";
            if (builder.length() + rendered.length() > maxChars) {
                break;
            }
            builder.append(rendered);
            count++;
            if (count >= maxItems) {
                break;
            }
        }
        return builder.toString().trim();
    }

    public void appendToPrompt(StringBuilder target, MemorySummary summary) {
        String section = render(summary);
        if (!section.isEmpty()) {
            target.append("\n\n").append(section);
        }
    }

    private static String safeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "偏好";
        }
        return oneLine(label, 40);
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank()) {
            return "无";
        }
        return oneLine(value, 120);
    }

    private static String oneLine(String value, int maxLength) {
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }
}
