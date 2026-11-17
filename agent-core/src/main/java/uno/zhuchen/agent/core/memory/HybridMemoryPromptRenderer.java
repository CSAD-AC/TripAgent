package uno.zhuchen.agent.core.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HybridMemoryPromptRenderer {

    private final boolean hybridEnabled;
    private final MemoryRetrievalService retrievalService;
    private final int maxChars;
    private final int maxItems;

    public HybridMemoryPromptRenderer(MemoryRetrievalService retrievalService,
                                      @Value("${app.memory.hybrid.enabled:false}") boolean hybridEnabled,
                                      @Value("${app.memory.hybrid.max-chars:1000}") int maxChars,
                                      @Value("${app.memory.hybrid.max-items:8}") int maxItems) {
        this.retrievalService = retrievalService;
        this.hybridEnabled = hybridEnabled;
        this.maxChars = Math.max(100, maxChars);
        this.maxItems = Math.max(1, maxItems);
    }

    public String render(String tenantId, String userId, String projectId, String query) {
        if (!hybridEnabled) {
            return "";
        }
        MemoryPromptContext context = retrievalService.load(tenantId, userId, projectId, query);
        if (context.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("[混合长期记忆]\n")
                .append("以下信息来自过去对话，仅作为参考；若与本次明确要求冲突，以本次要求为准：\n");
        int count = 0;
        for (String line : context.pinnedLines()) {
            if (!append(builder, line, count)) {
                return builder.toString().trim();
            }
            count++;
        }
        for (String line : context.episodicLines()) {
            if (!append(builder, line, count)) {
                return builder.toString().trim();
            }
            count++;
        }
        return builder.toString().trim();
    }

    public void appendTo(StringBuilder target, String tenantId, String userId, String projectId, String query) {
        String section = render(tenantId, userId, projectId, query);
        if (!section.isEmpty()) {
            target.append("\n\n").append(section);
        }
    }

    private boolean append(StringBuilder builder, String line, int count) {
        if (count >= maxItems || line == null || line.isBlank()) {
            return false;
        }
        String safe = line.replace('\r', ' ').replace('\n', ' ').trim();
        String rendered = "- " + (safe.length() > 160 ? safe.substring(0, 160) + "…" : safe) + "\n";
        if (builder.length() + rendered.length() > maxChars) {
            return false;
        }
        builder.append(rendered);
        return true;
    }
}
