package uno.zhuchen.agent.tools.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uno.zhuchen.agent.tools.cloud.config.SearchConfig;
import uno.zhuchen.agent.tools.cloud.model.ChannelStatus;
import uno.zhuchen.agent.tools.cloud.model.SearchOutcome;
import uno.zhuchen.agent.tools.cloud.model.SearchResult;
import uno.zhuchen.agent.tools.cloud.service.CloudSearchService.ChannelAttempt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行编排的融合, 降级与 provider 分派测试
 *
 * <p>{@link CloudSearchService#combine} 是纯函数, 不碰网络, 因此融合与降级行为可以离线验证;
 * provider 分派部分用 '未配置 key' 的分支验证, 同样不产生真实请求。
 *
 * <p>注意夹具里的正文必须彼此差异足够大: 去重是按正文 4-gram 相似度判定的,
 * 若造出来的摘要共享大段模板文字, 会被正确判定为近重复(这不是缺陷)。
 */
class SearchFusionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BODY_A =
            "西湖景区环湖区域免费开放, 雷峰塔门票四十元, 三潭印月船票五十五元, 热门点位需提前七天实名预约.";
    private static final String BODY_B =
            "灵隐飞来峰自二零二五年十二月一日起实行免票, 开放时间七点半至十七点半, 入园仍需实名预约.";
    private static final String BODY_C =
            "黄山北站到杭州东站高铁运行约一小时半, 二等座一百二十元, 每日始发多趟, 建议提前购票.";
    private static final String BODY_D =
            "成都大熊猫繁育研究基地七点半开园, 门票五十五元, 建议上午先看幼年熊猫再逛其他园区.";

    private static SearchResult hit(String title, String url, String snippet) {
        return new SearchResult(title, url, snippet);
    }

    private static ChannelAttempt ok(String name, List<SearchResult> hits) {
        return new ChannelAttempt(name, hits, 100L, null);
    }

    private static ChannelAttempt failed(String name, String error) {
        return new ChannelAttempt(name, List.of(), 50L, error);
    }

    @Test
    @DisplayName("一路故障时, 整体仍返回另一路结果, 且状态标记为降级")
    void oneChannelFailureDoesNotBreakSearch() {
        ChannelAttempt baidu = ok("baidu", List.of(
                hit("A", "https://a.example/1", BODY_A),
                hit("B", "https://b.example/2", BODY_B)));
        ChannelAttempt deepseek = failed("deepseek", "timeout");

        SearchOutcome outcome = CloudSearchService.combine(List.of(baidu, deepseek), 10);

        assertEquals(2, outcome.results().size(), "另一路的 2 条结果必须保留");
        assertTrue(outcome.degraded(), "有通道故障应标记降级");
        assertTrue(outcome.failures().contains("deepseek"), "故障原因必须透出");
        assertEquals("baidu", outcome.contributedChannels());
    }

    @Test
    @DisplayName("全部通道故障时返回空结果, 不抛异常")
    void allChannelsFailedReturnsEmpty() {
        SearchOutcome outcome = CloudSearchService.combine(
                List.of(failed("baidu", "HTTP 500"), failed("deepseek", "HTTP 429")), 10);

        assertTrue(outcome.results().isEmpty(), "全部失败应返回空结果, 由上层降级");
        assertTrue(outcome.degraded());
        assertEquals(2, outcome.channels().size(), "两路状态都要保留, 便于排障");
    }

    @Test
    @DisplayName("某一路正常但零结果, 也算降级但整体不受影响")
    void emptyChannelIsDegradedButHarmless() {
        ChannelAttempt baidu = ok("baidu", List.of(hit("A", "https://a.example/1", BODY_A)));
        ChannelAttempt deepseek = ok("deepseek", List.of());

        SearchOutcome outcome = CloudSearchService.combine(List.of(baidu, deepseek), 10);

        assertEquals(1, outcome.results().size());
        assertTrue(outcome.degraded(), "零结果通道应被识别为未贡献");
        assertFalse(outcome.failures().contains("deepseek"), "零结果不是故障, 不应算失败");
        assertEquals("baidu", outcome.contributedChannels());
    }

    @Test
    @DisplayName("RRF: 两路都收录的 URL 应排到第一位")
    void rrfPromotesUrlPresentInBothChannels() {
        ChannelAttempt baidu = ok("baidu", List.of(
                hit("A", "https://a.example/1", BODY_A),
                hit("B", "https://b.example/2", BODY_B)));
        ChannelAttempt deepseek = ok("deepseek", List.of(
                hit("B", "https://b.example/2", BODY_B),
                hit("C", "https://c.example/3", BODY_C)));

        SearchOutcome outcome = CloudSearchService.combine(List.of(baidu, deepseek), 10);

        List<String> urls = outcome.results().stream().map(SearchResult::url).toList();
        assertEquals(3, urls.size(), "共同 URL 只能出现一次: " + urls);
        assertEquals("https://b.example/2", urls.get(0), "两路都收录的应因 RRF 分数最高而排第一");
    }

    @Test
    @DisplayName("跨通道正文近重复: 同一篇文章只保留一条")
    void crossChannelNearDuplicateCollapses() {
        // 同一篇软文的正文, 被两个引擎以不同标题/域名收录
        String sameBody = BODY_A;
        ChannelAttempt baidu = ok("baidu", List.of(
                hit("软文标题甲", "https://spam.example/a", sameBody)));
        ChannelAttempt deepseek = ok("deepseek", List.of(
                hit("软文标题乙", "https://mirror.example/b", sameBody)));

        SearchOutcome outcome = CloudSearchService.combine(List.of(baidu, deepseek), 10);

        assertEquals(1, outcome.results().size(), "正文相同的两条应只保留一条");
    }

    @Test
    @DisplayName("单路供数时直接截断到 limit, 不做无意义的融合")
    void singleContributingChannelIsTruncated() {
        List<SearchResult> many = List.of(
                hit("A", "https://a.example/1", BODY_A),
                hit("B", "https://b.example/2", BODY_B),
                hit("C", "https://c.example/3", BODY_C),
                hit("D", "https://d.example/4", BODY_D));
        SearchOutcome outcome = CloudSearchService.combine(
                List.of(ok("baidu", many), failed("deepseek", "HTTP 401")), 2);

        assertEquals(2, outcome.results().size());
        assertTrue(outcome.degraded(), "deepseek 故障应标记降级");
        assertEquals("baidu", outcome.contributedChannels());
    }

    @Test
    @DisplayName("两路都有产出时按 limit 截断")
    void fuseRespectsLimit() {
        ChannelAttempt baidu = ok("baidu", List.of(
                hit("A", "https://a.example/1", BODY_A),
                hit("B", "https://b.example/2", BODY_B)));
        ChannelAttempt deepseek = ok("deepseek", List.of(
                hit("C", "https://c.example/3", BODY_C),
                hit("D", "https://d.example/4", BODY_D)));

        SearchOutcome outcome = CloudSearchService.combine(List.of(baidu, deepseek), 3);

        assertEquals(3, outcome.results().size());
    }

    @Test
    @DisplayName("全空输入安全返回")
    void emptyInputIsSafe() {
        SearchOutcome outcome = CloudSearchService.combine(List.of(), 10);
        assertTrue(outcome.results().isEmpty());
        assertTrue(outcome.channels().isEmpty());
    }

    @Test
    @DisplayName("通道状态记录条数/耗时/错误")
    void channelStatusCarriesDiagnostics() {
        ChannelAttempt baidu = new ChannelAttempt("baidu", List.of(
                hit("A", "https://a.example/1", BODY_A)), 321L, null);
        ChannelAttempt deepseek = new ChannelAttempt("deepseek", List.of(), 87L, "HTTP 429 (rate limited)");

        SearchOutcome outcome = CloudSearchService.combine(List.of(baidu, deepseek), 10);
        List<ChannelStatus> statuses = outcome.channels();

        assertEquals(1, statuses.get(0).hits());
        assertEquals(321L, statuses.get(0).latencyMs());
        assertTrue(statuses.get(0).ok());
        assertEquals(0, statuses.get(1).hits());
        assertEquals("HTTP 429 (rate limited)", statuses.get(1).error());
        assertFalse(statuses.get(1).ok());
    }

    @Test
    @DisplayName("provider=baidu 但未配置 key: 不发请求, 状态为 not configured")
    void missingKeyYieldsNotConfiguredStatus() {
        SearchConfig config = new SearchConfig();
        config.setProvider("baidu");

        SearchOutcome outcome = new CloudSearchService(config, MAPPER).searchDetailed("任意查询", 5);

        assertTrue(outcome.results().isEmpty());
        assertEquals(1, outcome.channels().size(), "被指定的通道仍要上报状态");
        assertEquals("not configured", outcome.channels().get(0).error());
    }

    @Test
    @DisplayName("同 URL 两路都有时, 摘要应保留更长的那条(不丢 DeepSeek 长摘要)")
    void sameUrlKeepsLongerSnippet() {
        String shortSnippet = "西湖环湖免费开放, 雷峰塔门票四十元.";
        String longSnippet = shortSnippet + " 灵隐飞来峰自二零二五年十二月起免票, 开放时间七点半至十七点半, "
                + "需实名预约, 旺季名额提前三天放完, 建议错峰出行并关注官方公众号通知.";
        ChannelAttempt baidu = new ChannelAttempt("baidu", List.of(
                new SearchResult("西湖预约", "https://same.example/1", shortSnippet,
                        "网易", "2026-10-23", 0.5, 1.0)), 100L, null);
        ChannelAttempt deepseek = new ChannelAttempt("deepseek", List.of(
                new SearchResult("西湖预约", "https://same.example/1", longSnippet,
                        "Trip.com", "2026-07-30", 3.0, 2.1)), 90L, null);

        SearchOutcome outcome = CloudSearchService.combine(List.of(baidu, deepseek), 5);

        assertEquals(1, outcome.results().size(), "同一 URL 只能出现一次");
        SearchResult merged = outcome.results().get(0);
        assertEquals(longSnippet, merged.snippet(), "应保留信息量更大的长摘要");
        assertEquals("网易", merged.siteName(), "站点名保留先出现的非空值");
    }

    @Test
    @DisplayName("夹具正文长度必须超过去重阈值, 否则用例会静默空跑")
    void fixturesAreLongEnoughForDedup() {
        for (String body : List.of(BODY_A, BODY_B, BODY_C, BODY_D)) {
            assertTrue(body.length() >= 40, "夹具正文需 >= 40 字, 否则不参与去重, 用例会假绿");
        }
    }

    @Test
    @DisplayName("parallel 但两把 key 都没配: 每个通道都要上报 not configured")
    void parallelWithoutKeysReportsBothChannels() {
        SearchConfig config = new SearchConfig();
        config.setProvider("parallel");

        SearchOutcome outcome = new CloudSearchService(config, MAPPER).searchDetailed("任意查询", 5);

        assertTrue(outcome.results().isEmpty());
        assertEquals(2, outcome.channels().size(), "未配置的通道也必须上报状态, 不能静默退化成单路");
        assertTrue(outcome.channels().stream().allMatch(c -> "not configured".equals(c.error())),
                "缺 key 的通道应报 not configured: " + outcome.channels());
        assertTrue(outcome.degraded());
    }

    @Test
    @DisplayName("parallel 双主通道均失败后, 自动使用 Tavily 兜底")
    void parallelFallsBackToTavilyWhenBothPrimaryChannelsFail() {
        SearchConfig config = new SearchConfig();
        config.setProvider("parallel");
        config.setChannelTimeoutSeconds(2);

        SearchClient baidu = (query, limit) -> {
            throw new SearchChannelException("baidu down");
        };
        SearchClient deepseek = (query, limit) -> {
            throw new SearchChannelException("deepseek down");
        };
        SearchClient tavily = (query, limit) -> List.of(
                hit("Fallback", "https://fallback.example/1", BODY_D));

        CloudSearchService service = new CloudSearchService(
                config, baidu, deepseek, tavily);
        try {
            SearchOutcome outcome = service.searchDetailed("成都景点", 5);

            assertEquals(1, outcome.results().size());
            assertEquals("https://fallback.example/1", outcome.results().get(0).url());
            assertEquals("tavily", outcome.contributedChannels());
            assertEquals(3, outcome.channels().size());
        } finally {
            service.shutdown();
        }
    }

    @Test
    @DisplayName("主通道有结果时不调用 Tavily 兜底")
    void tavilyFallbackSkippedWhenPrimaryChannelContributes() {
        SearchConfig config = new SearchConfig();
        config.setProvider("parallel");
        config.setChannelTimeoutSeconds(2);

        SearchClient baidu = (query, limit) -> List.of(
                hit("Primary", "https://primary.example/1", BODY_A));
        SearchClient deepseek = (query, limit) -> {
            throw new SearchChannelException("deepseek down");
        };
        AtomicInteger fallbackCalls = new AtomicInteger();
        SearchClient tavily = (query, limit) -> {
            fallbackCalls.incrementAndGet();
            return List.of(hit("Fallback", "https://fallback.example/1", BODY_D));
        };

        CloudSearchService service = new CloudSearchService(
                config, baidu, deepseek, tavily);
        try {
            SearchOutcome outcome = service.searchDetailed("西湖景点", 5);

            assertEquals(1, outcome.results().size());
            assertEquals("baidu", outcome.contributedChannels());
            assertEquals(0, fallbackCalls.get());
            assertEquals(2, outcome.channels().size());
        } finally {
            service.shutdown();
        }
    }

    @Test
    @DisplayName("空 query 不发起任何通道")
    void blankQueryShortCircuits() {
        SearchConfig config = new SearchConfig();
        SearchOutcome outcome = new CloudSearchService(config, MAPPER).searchDetailed("  ", 5);

        assertTrue(outcome.results().isEmpty());
        assertTrue(outcome.channels().isEmpty());
    }
}
