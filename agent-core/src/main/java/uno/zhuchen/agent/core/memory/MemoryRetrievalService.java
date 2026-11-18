package uno.zhuchen.agent.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
public class MemoryRetrievalService {

    private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.800");
    private static final BigDecimal DEFAULT_IMPORTANCE = new BigDecimal("0.500");

    /**
     * 占位值黑名单: LLM 抽取偶尔会把 value 写成 true/false 这类标记, 这类值注入提示词
     * 毫无信息量(典型坏例: "偏好：true"), 宁可不注入也不污染上下文。
     */
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "true", "false", "yes", "no", "1", "0", "是", "否", "有", "无", "null", "none", "unknown");

    /** 元问题: 用户在问"你记得我什么", 这时不该按相关性过滤, 应按时间列出近期经历 */
    private static final Pattern META_RECALL_QUERY = Pattern.compile(
            "(记得|记忆|记住|还记得|之前|上次|以前|过去|历史|去过|玩过|聊过|是什么来着)");

    private static final Pattern NOISE = Pattern.compile("[\\s\\p{Punct}，。！？、；：“”‘’（）《》·…]+");

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
                                  @Value("${app.memory.hybrid.min-score:0.55}") double minScore) {
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
                .filter(item -> meaningful(item.value()))
                .sorted(Comparator.comparing(MemoryItemRecord::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(finalTopK)
                .map(item -> labelFor(item) + "：" + item.value())
                .toList();

        if (!hybridEnabled || query == null || query.isBlank()) {
            return new MemoryPromptContext(pinned, List.of(), suppressions);
        }

        List<MemoryItemRecord> episodicCandidates = all.stream()
                .filter(item -> item.type() == MemoryItemType.EPISODIC)
                .filter(item -> !suppressed(item, suppressions))
                .filter(item -> meaningful(item.value()))
                .toList();

        Map<String, Double> vectorScores = vectorScores(tenantId, userId, projectId, query);
        List<MemoryItemRecord> episodic = episodicCandidates.stream()
                .map(item -> new ScoredItem(item, score(item, query, vectorScores.get(item.id()))))
                .filter(scored -> scored.score >= minScore)
                .sorted(Comparator.comparingDouble(ScoredItem::score).reversed())
                .limit(recallTopK)
                .map(ScoredItem::item)
                .limit(finalTopK)
                .toList();

        if (episodic.isEmpty() && isMetaRecallQuery(query)) {
            // "你记得什么"/"我之前去过哪"这类元问题本身没有检索词, 打分必然全灭:
            // 此时按时间倒序直出近期经历, 而不是让模型回答"我没有记忆"。
            episodic = episodicCandidates.stream()
                    .sorted(Comparator.comparing(MemoryItemRecord::updatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(finalTopK)
                    .toList();
        }

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
        double lexical = lexicalOverlap(haystack, query);
        return 0.55 * lexical + 0.20 * recency + 0.15 * confidence + 0.10 * importance;
    }

    /**
     * 词面相关度(0~1)。
     *
     * <p>早前实现用"整句包含"判定, 用户问"京都五日游你还记得吗"时它与
     * "上次规划过京都五日游, 住四条河原町"整句永远不相等, 词面分恒为 0, 记忆召回名存实亡。
     * 这里改成 CJK 二元组覆盖率: 把查询切成 bigram, 统计有多少落在记忆文本里。
     */
    private static double lexicalOverlap(String haystack, String query) {
        String hay = NOISE.matcher(haystack.toLowerCase(Locale.ROOT)).replaceAll("");
        String needle = NOISE.matcher(query.toLowerCase(Locale.ROOT)).replaceAll("");
        if (hay.isEmpty() || needle.isEmpty()) {
            return 0.0;
        }
        if (hay.contains(needle)) {
            return 1.0;
        }
        Set<String> queryGrams = bigrams(needle);
        if (queryGrams.isEmpty()) {
            return 0.0;
        }
        Set<String> hayGrams = bigrams(hay);
        long hit = queryGrams.stream().filter(hayGrams::contains).count();
        return (double) hit / queryGrams.size();
    }

    private static Set<String> bigrams(String text) {
        if (text.length() < 2) {
            return text.isEmpty() ? Set.of() : Set.of(text);
        }
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            grams.add(text.substring(i, i + 2));
        }
        return grams;
    }

    private static boolean isMetaRecallQuery(String query) {
        return META_RECALL_QUERY.matcher(query).find();
    }

    /** value 是否携带有效信息(过滤 true/false/数字等占位值) */
    private static boolean meaningful(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return !PLACEHOLDER_VALUES.contains(trimmed.toLowerCase(Locale.ROOT)) && trimmed.length() >= 2;
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
