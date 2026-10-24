package uno.zhuchen.agent.tools.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本归一化与近重复判定的单元测试
 *
 * <p>这些规则同时作用于三个通道的解析与融合阶段, 一旦判错会直接影响结果质量:
 * 判松了(漏判)会同一篇文章重复占位, 判紧了(误杀)会把不同攻略当成重复删掉。
 */
class SearchTextTest {

    /** 一篇攻略的正文头部(百度侧典型长度, 约 60 字) */
    private static final String SHORT_EXCERPT =
            "西湖环湖区域免费开放, 雷峰塔门票四十元, 三潭印月船票五十五元, 热门点位需提前七天实名预约.";

    /** 同一篇文章被 DeepSeek 摘出来的长摘要(前 60 字与上面完全一致, 后接更多内容) */
    private static final String LONG_SUMMARY = SHORT_EXCERPT
            + " 灵隐飞来峰自二零二五年十二月一日起实行免票, 开放时间七点半至十七点半, 入园仍需实名预约, "
            + "旺季名额通常提前三天放完, 建议错峰出行并留意景区官方公众号的实时通告.";

    @Test
    @DisplayName("完全相同的正文判定为同一篇")
    void identicalTextIsSameArticle() {
        assertTrue(SearchText.sameArticle(SHORT_EXCERPT, SHORT_EXCERPT));
    }

    @Test
    @DisplayName("短摘录与同文长摘要: 长度差数倍也要判为同一篇")
    void shortExcerptContainedInLongSummaryIsSameArticle() {
        // 这正是跨通道去重的真实场景: 百度给短摘录, DeepSeek 给长摘要
        assertTrue(SearchText.sameArticle(SHORT_EXCERPT, LONG_SUMMARY),
                "包含度判定应识别出短摘录出自同一篇长摘要");
        assertTrue(SearchText.sameArticle(LONG_SUMMARY, SHORT_EXCERPT), "判定必须对称");
    }

    @Test
    @DisplayName("不同景点的正文不能误判为同一篇")
    void differentArticlesAreNotSame() {
        String other = "成都大熊猫繁育研究基地七点半开园, 门票五十五元, "
                + "建议上午先看幼年熊猫, 园区观光车十元一次, 可省大量步行.";
        assertFalse(SearchText.sameArticle(SHORT_EXCERPT, other));
    }

    @Test
    @DisplayName("过长与过短的组合受长度比守卫保护")
    void lengthRatioGuardPreventsFalsePositive() {
        // 极短片段被超长页面包含时不应判定为同一篇
        String tiny = "西湖免费开放";
        assertFalse(SearchText.sameArticle(tiny, LONG_SUMMARY + LONG_SUMMARY + LONG_SUMMARY));
    }

    @Test
    @DisplayName("短于 40 字的摘要不参与去重")
    void tooShortTextIsNotCompared() {
        assertTrue(SearchText.shinglesOf("太短了").isEmpty());
        assertFalse(SearchText.sameArticle("太短了", "太短了"));
    }

    @Test
    @DisplayName("日期归一化: 百度空格格式与 DeepSeek RFC3339 都收敛到 yyyy-MM-dd")
    void normalizeDateHandlesBothFormats() {
        assertEquals("2026-10-23", SearchText.normalizeDate("2026-10-23 14:08:42"));
        assertEquals("2026-10-23", SearchText.normalizeDate("2026-10-23T14:08:42+00:00"));
        assertEquals("2026-10-23", SearchText.normalizeDate("  2026-10-23  "));
        assertNull(SearchText.normalizeDate(null));
        assertNull(SearchText.normalizeDate("   "));
    }

    @Test
    @DisplayName("标题归一化: 标点与空白差异不影响同一文章判定")
    void normalizeTitleIgnoresPunctuation() {
        String a = SearchText.normalizeTitle("西湖景区 最新 开放时间: 预约指南!");
        String b = SearchText.normalizeTitle("西湖景区最新开放时间 预约指南");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("通道内去重: 同 URL / 同标题 / 同正文都只留一条")
    void dedupeCollapsesAllThreeKinds() {
        List<uno.zhuchen.agent.tools.cloud.model.SearchResult> items = List.of(
                new uno.zhuchen.agent.tools.cloud.model.SearchResult("标题甲", "https://a.example/1", SHORT_EXCERPT),
                // 同 URL
                new uno.zhuchen.agent.tools.cloud.model.SearchResult("标题乙", "https://a.example/1", LONG_SUMMARY),
                // 同标题不同 URL
                new uno.zhuchen.agent.tools.cloud.model.SearchResult("标题甲", "https://b.example/2", LONG_SUMMARY),
                // 同正文不同标题不同 URL
                new uno.zhuchen.agent.tools.cloud.model.SearchResult("标题丙", "https://c.example/3", SHORT_EXCERPT));

        var deduped = SearchText.dedupe(items,
                new java.util.ArrayList<>(items).size());

        assertEquals(1, deduped.size(), "三种重复都应收敛为一条: " + deduped);
    }
}
