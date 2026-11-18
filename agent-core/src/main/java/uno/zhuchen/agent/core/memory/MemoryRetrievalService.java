package uno.zhuchen.agent.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class MemoryRetrievalService {

    private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.800");
    private static final BigDecimal DEFAULT_IMPORTANCE = new BigDecimal("0.500");

    private final MemoryItemService memoryItemService;
    private final MemoryVectorService vectorService;
    private final boolean hybridEnabled;
    private final int recallTopK;
    private final int finalTopK;
    private final double minScore;

    public MemoryRetrievalService(MemoryItemService memoryItemService,
                                  MemoryVectorService vectorService,
                                  @Value("${app.memory.hybrid.enabled:false}") boolean hybridEnabled,
                                  @Value("${app.memory.hybrid.recall-top-k:20}") int recallTopK,
                                  @Value("${app.memory.hybrid.final-top-k:6}") int finalTopK,
                                  @Value("${app.memory.hybrid.min-score:0.62}") double minScore) {
        this.memoryItemService = memoryItemService;
        this.vectorService = vectorService;
        this.hybridEnabled = hybridEnabled;
        this.recallTopK = Math.max(1, recallTopK);
        this.finalTopK = Math.max(1, finalTopK);
        this.minScore = minScore;
        log.info("[memory-recall] 初始化: hybridEnabled={}, vectorService={}, recallTopK={}, finalTopK={}, minScore={}",
                hybridEnabled, vectorService.getClass().getSimpleName(),
                this.recallTopK, this.finalTopK, this.minScore);
    }

    public MemoryPromptContext load(String tenantId, String userId, String projectId, String query) {
        List<MemoryItemRecord> all = memoryItemService.list(tenantId, userId, projectId);
        List<String> suppressions = all.stream()
                .filter(item -> item.type() == MemoryItemType.VETO)
                .map(MemoryItemRecord::value)
                .toList();

        List<String> pinned = all.stream()
                .filter(item -> item.type() != MemoryItemType.EPISODIC
                        && item.type() != MemoryItemType.VETO)
                .filter(item -> !suppressed(item, suppressions))
                .sorted(Comparator.comparing(MemoryItemRecord::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(finalTopK)
                .map(item -> labelFor(item) + "：" + item.value())
                .toList();

        if (!hybridEnabled || query == null || query.isBlank()) {
            return new MemoryPromptContext(pinned, List.of(), suppressions);
        }

        Map<String, Double> vectorScores = vectorScores(tenantId, userId, projectId, query);

        List<MemoryItemRecord> episodic = all.stream()
                .filter(item -> item.type() == MemoryItemType.EPISODIC)
                .filter(item -> !suppressed(item, suppressions))
                .map(item -> new ScoredItem(item, score(item, query, vectorScores.get(item.id()))))
                .filter(scored -> scored.score >= minScore)
                .sorted(Comparator.comparingDouble(ScoredItem::score).reversed())
                .limit(recallTopK)
                .map(ScoredItem::item)
                .limit(finalTopK)
                .toList();

        List<String> episodicLines = episodic.stream()
                .map(item -> "经历：" + item.value())
                .toList();
        log.debug("[memory-recall] userId={}, query={}, pinned={}, episodic={}, suppressions={}",
                userId, query, pinned.size(), episodicLines.size(), suppressions.size());
        return new MemoryPromptContext(pinned, episodicLines, suppressions);
    }

    /**
     * 语义召回: 向量通道可用时按用户维度取回相关经历, 失败返回空表由词面打分兜底。
     */
    private Map<String, Double> vectorScores(String tenantId, String userId, String projectId, String query) {
        if (!vectorService.available()) {
            return Map.of();
        }
        try {
            List<MemoryVectorHit> hits = vectorService.search(
                    tenantId, userId, projectId, query, recallTopK, minScore);
            if (hits.isEmpty()) {
                return Map.of();
            }
            Map<String, Double> scores = new HashMap<>();
            hits.forEach(hit -> scores.merge(hit.id(), hit.score(), Math::max));
            return scores;
        } catch (Exception e) {
            log.warn("[memory] 语义召回失败, 回退词面打分: error={}", e.getMessage());
            return Map.of();
        }
    }

    private double score(MemoryItemRecord item, String query, Double vectorScore) {
        double confidence = item.confidence() == null
                ? DEFAULT_CONFIDENCE.doubleValue() : item.confidence().doubleValue();
        double importance = item.importance() == null
                ? DEFAULT_IMPORTANCE.doubleValue() : item.importance().doubleValue();
        double recency = item.updatedAt() == null ? 0.0 : 0.8;
        if (vectorScore != null) {
            // 有语义分时以语义分主, 重要度/置信度/新鲜度做微调
            return 0.65 * vectorScore + 0.15 * importance + 0.10 * confidence + 0.10 * recency;
        }
        String haystack = (item.key() + " " + item.value() + " " + item.content())
                .toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        double lexical = haystack.contains(needle) ? 1.0 : 0.0;
        return 0.55 * lexical + 0.20 * recency + 0.15 * confidence + 0.10 * importance;
    }

    private boolean suppressed(MemoryItemRecord item, List<String> suppressions) {
        if (suppressions.isEmpty()) {
            return false;
        }
        String text = (item.key() + " " + item.value()).toLowerCase(Locale.ROOT);
        return suppressions.stream().anyMatch(rule -> rule != null && !rule.isBlank()
                && text.contains(rule.toLowerCase(Locale.ROOT)));
    }

    private static String labelFor(MemoryItemRecord item) {
        return switch (item.type()) {
            case PROFILE -> "用户信息";
            case CONSTRAINT -> "规划约束";
            case PREFERENCE -> "偏好";
            case EPISODIC -> "经历";
            case VETO -> "抑制规则";
        };
    }

    private record ScoredItem(MemoryItemRecord item, double score) {
    }
}
