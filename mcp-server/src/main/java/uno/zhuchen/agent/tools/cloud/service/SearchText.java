package uno.zhuchen.agent.tools.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import uno.zhuchen.agent.tools.cloud.model.SearchResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索通道共用的文本处理工具
 *
 * <p>三个通道(百度/DeepSeek/Tavily)的响应字段名不同, 但归一化与去重规则必须一致,
 * 否则同一篇文章在不同通道会被判定成不同内容, 融合阶段就白做了。
 */
final class SearchText {

    /** 日期前缀提取, 用于把 "2026-10-23 14:08:42" / "2026-10-23T14:08:42+00:00" 归一化 */
    private static final Pattern DATE_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");

    /** 空白符压缩, 用于正文去重前的归一化 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** 标题归一化时剔除的符号(中英文标点与空白) */
    private static final Pattern TITLE_NOISE = Pattern.compile("[\\s\\p{Punct}]+|[\u3000-\u303f\uff00-\uffef]");

    /** 近重复检测的 shingle 长度(字符) */
    private static final int SHINGLE = 4;

    /** 包含度阈值: 短文本的 shingle 有多少比例出现在另一侧 */
    private static final double CONTAINMENT_THRESHOLD = 0.9;

    /**
     * 参与比对的长度比下限
     *
     * <p>两路对同一篇文章给出的摘录长度差异很大(百度约 150 字, DeepSeek 长摘要 600~800 字),
     * 4-gram 的 Jaccard 只有 0.19 左右, 会被 0.9 的对称阈值漏判, 所以改用"包含度 + 长度比"
     * 的不对称判定。长度比下限用于拦住"短摘要恰好被长页面模板包含"的误杀。
     */
    private static final double MIN_LENGTH_RATIO = 0.15;

    /** 参与去重比对的最大字符数, 控制比对开销 */
    private static final int DEDUPE_MAX_CHARS = 2000;

    /** 正文去重最小长度, 过短的摘要不参与去重(避免误杀) */
    private static final int DEDUPE_MIN_LENGTH = 40;

    private SearchText() {
    }

    /**
     * 读取文本字段, 缺失或 null 时返回空串。
     *
     * @param node  JSON 节点
     * @param field 字段名
     * @return 字段文本, 缺失时为空串(不返回 null, 便于调用方拼逻辑)
     */
    static String textOf(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : "";
    }

    /**
     * 读取数值字段, 缺失或非数值时返回 null。
     *
     * @param node  JSON 节点
     * @param field 字段名
     * @return 数值, 缺失时 null
     */
    static Double doubleOf(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.asDouble() : null;
    }

    /**
     * 归一化发布时间为 yyyy-MM-dd。
     *
     * <p>百度返回 2026-10-23 14:08:42, DeepSeek 返回 RFC3339, 时区语义均不可靠,
     * 因此只保留日期部分, 避免向 LLM 传达不存在的精确度。无法识别时原样返回。
     *
     * @param raw 原始日期文本
     * @return 归一化日期, 输入为空时 null
     */
    static String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = DATE_PREFIX.matcher(raw.trim());
        return m.find() ? m.group() : raw.trim();
    }

    /**
     * 标题归一化: 去空白与标点并转小写, 用于跨通道同一文章的兜底比对。
     *
     * @param title 原始标题
     * @return 归一化标题, 输入为空时返回空串
     */
    static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return TITLE_NOISE.matcher(title).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * 通道内去重 + 截断
     *
     * <p>三层: 同 URL / 同标题(跨域名转载) / 正文近重复(SEO 农场换标题铺量)。
     *
     * @param items 原始结果, 顺序按通道给出的相关度
     * @param limit 最多保留条数
     * @return 去重后的结果
     */
    static List<SearchResult> dedupe(List<SearchResult> items, int limit) {
        List<SearchResult> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        List<String> keptBodies = new ArrayList<>();
        for (SearchResult r : items) {
            if (out.size() >= limit) {
                break;
            }
            String url = r.url() == null ? "" : r.url();
            String title = r.title() == null ? "" : r.title();
            if (url.isEmpty() || title.isEmpty()) {
                continue;
            }
            if (!seenUrls.add(url) || !seenTitles.add(title)) {
                continue;
            }
            String body = r.snippet() == null ? "" : r.snippet();
            boolean duplicated = false;
            for (String kept : keptBodies) {
                if (sameArticle(kept, body)) {
                    duplicated = true;
                    break;
                }
            }
            if (duplicated) {
                continue;
            }
            keptBodies.add(body);
            out.add(r);
        }
        return out;
    }

    /**
     * 判定两段正文是否为同一篇文章
     *
     * <p>用不对称包含度而非 Jaccard: 两路摘录长度可能差 5 倍, 短摘录往往完整落在长摘要里,
     * 此时 Jaccard 只有约 0.2, 但包含度接近 1。
     *
     * @param bodyA 正文 A
     * @param bodyB 正文 B
     * @return true 表示判定为同一篇内容
     */
    static boolean sameArticle(String bodyA, String bodyB) {
        Set<Integer> a = shinglesOf(bodyA);
        Set<Integer> b = shinglesOf(bodyB);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        int small = Math.min(a.size(), b.size());
        int large = Math.max(a.size(), b.size());
        if ((double) small / large < MIN_LENGTH_RATIO) {
            return false;
        }
        int inter = 0;
        for (Integer x : a) {
            if (b.contains(x)) {
                inter++;
            }
        }
        return (double) inter / small >= CONTAINMENT_THRESHOLD;
    }

    /**
     * 正文指纹 - 压缩空白后的 4-gram 集合。
     *
     * @param snippet 摘要或正文
     * @return 指纹集合, 空集合表示该条不参与去重
     */
    static Set<Integer> shinglesOf(String snippet) {
        if (snippet == null) {
            return Set.of();
        }
        String norm = WHITESPACE.matcher(snippet).replaceAll("");
        if (norm.length() < DEDUPE_MIN_LENGTH) {
            return Set.of();
        }
        int end = Math.min(norm.length(), DEDUPE_MAX_CHARS);
        Set<Integer> set = new HashSet<>();
        for (int i = 0; i + SHINGLE <= end; i++) {
            set.add(norm.substring(i, i + SHINGLE).hashCode());
        }
        return set;
    }

    /**
     * 同一 URL 被两路都收录时的字段合并
     *
     * <p>排序仍由 RRF 按名次决定, 但展示字段要择优: 两路摘录长度差 5 倍,
     * 只保留先出现者会系统性丢掉 DeepSeek 的长摘要(summary)与站点名,
     * 而这正是引入第二路的目的。
     *
     * @param base  先出现的结果
     * @param other 另一路的结果
     * @return 合并后的结果
     */
    static SearchResult merge(SearchResult base, SearchResult other) {
        if (other == null) {
            return base;
        }
        String snippet = longer(base.snippet(), other.snippet());
        return new SearchResult(
                base.title(),
                base.url(),
                snippet,
                firstNonBlank(base.siteName(), other.siteName()),
                firstNonBlank(base.date(), other.date()),
                base.authority() != null ? base.authority() : other.authority(),
                base.rerankScore() != null ? base.rerankScore() : other.rerankScore());
    }

    private static String longer(String a, String b) {
        int la = a == null ? 0 : a.length();
        int lb = b == null ? 0 : b.length();
        return la >= lb ? a : b;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
