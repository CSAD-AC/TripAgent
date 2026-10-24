package uno.zhuchen.agent.mcpserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uno.zhuchen.agent.tools.cloud.config.SearchConfig;
import uno.zhuchen.agent.tools.cloud.model.ChannelStatus;
import uno.zhuchen.agent.tools.cloud.model.SearchOutcome;
import uno.zhuchen.agent.tools.cloud.model.SearchResult;
import uno.zhuchen.agent.tools.cloud.service.CloudSearchService;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 通道效果对照测试 - 百度 Qianfan vs DeepSeek Search
 *
 * <p>固定 provider 分别跑单通道, 对比两路的站点分布与重合度, 用于接入新通道或调整
 * 融合策略后做回归观察. 与 {@link SearchChannelIntegrationTest} 分工:
 * 本类只观察 '两路结果差异', 不做降级与融合断言.
 *
 * <p>更细的手工对照(逐条标题/日期/权威性, 以及 DeepSeek 独有搜索语法验证)
 * 请用仓库根的 {@code tools/search_compare.py}.
 *
 * <p>需要网络 + 两个 Key, 缺失时自动跳过.
 */
@Tag("integration")
class SearchProviderComparisonTest {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderComparisonTest.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> QUERIES = List.of(
            "西湖景区 最新 开放时间 预约",
            "12306 学生票 规则 优惠",
            "成都 三日游 本地人 推荐 路线"
    );

    private static final int COUNT = 6;

    @Test
    @DisplayName("两路结果站点重合度统计")
    void compareChannelSiteOverlap() {
        String baiduKey = key("baidu.api-key", "BAIDU_API_KEY");
        String deepseekKey = key("deepseek.api-key", "DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(baiduKey != null && deepseekKey != null,
                "需要同时配置 BAIDU_API_KEY 与 DEEPSEEK_API_KEY");

        CloudSearchService baiduOnly = service("baidu", baiduKey, null);
        CloudSearchService deepseekOnly = service("deepseek", null, deepseekKey);

        for (String query : QUERIES) {
            SearchOutcome baidu = baiduOnly.searchDetailed(query, COUNT);
            SearchOutcome deepseek = deepseekOnly.searchDetailed(query, COUNT);

            Set<String> baiduHosts = hosts(baidu.results());
            Set<String> deepseekHosts = hosts(deepseek.results());
            double jaccard = jaccard(baiduHosts, deepseekHosts);

            log.info("QUERY: {}", query);
            log.info("  baidu    hits={} withDate={} hosts={}",
                    baidu.results().size(), withDate(baidu.results()), baiduHosts);
            log.info("  deepseek hits={} withDate={} hosts={}",
                    deepseek.results().size(), withDate(deepseek.results()), deepseekHosts);
            log.info("  host overlap Jaccard={}", String.format(Locale.ROOT, "%.2f", jaccard));
        }
    }

    @Test
    @DisplayName("单通道状态可观测: 有 key 的通道应报告真实条数")
    void singleChannelStatusIsObservable() {
        String baiduKey = key("baidu.api-key", "BAIDU_API_KEY");
        Assumptions.assumeTrue(baiduKey != null, "需要 BAIDU_API_KEY");

        SearchOutcome outcome = service("baidu", baiduKey, null).searchDetailed("北京旅游景点", 3);

        Assumptions.assumeFalse(outcome.channels().isEmpty(), "通道未初始化");
        ChannelStatus status = outcome.channels().get(0);
        log.info("baidu channel: hits={}, latencyMs={}, error={}",
                status.hits(), status.latencyMs(), status.error());
        Assumptions.assumeTrue(status.ok(), "channel failed, skip assertion: " + status.error());
        org.junit.jupiter.api.Assertions.assertTrue(status.hits() > 0, "正常通道应返回结果");
    }

    // ==================== tools ====================

    private static CloudSearchService service(String provider, String baiduKey, String deepseekKey) {
        SearchConfig config = new SearchConfig();
        config.setProvider(provider);
        config.setBaiduApiKey(baiduKey);
        config.setDeepseekApiKey(deepseekKey);
        config.setChannelTimeoutSeconds(20);
        return new CloudSearchService(config, MAPPER);
    }

    private static Set<String> hosts(List<SearchResult> results) {
        Set<String> hosts = new LinkedHashSet<>();
        for (SearchResult r : results) {
            try {
                String host = URI.create(r.url()).getHost();
                if (host != null) {
                    hosts.add(host);
                }
            } catch (Exception ignored) {
                // 忽略无法解析的 URL
            }
        }
        return hosts;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        List<String> inter = new ArrayList<>(a);
        inter.retainAll(b);
        return (double) inter.size() / union.size();
    }

    private static long withDate(List<SearchResult> results) {
        return results.stream().filter(r -> r.date() != null && !r.date().isBlank()).count();
    }

    private static String key(String sysProp, String envName) {
        String v = System.getProperty(sysProp);
        if (v != null && !v.isBlank()) {
            return v;
        }
        v = System.getenv(envName);
        if (v != null && !v.isBlank()) {
            return v;
        }
        for (String f : new String[]{"src/main/resources/.env", "../agent-core/src/main/resources/.env"}) {
            v = readEnv(Path.of(f), envName);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String readEnv(Path path, String key) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).trim().equals(key)) {
                    return trimmed.substring(eq + 1).trim().replace("\"", "");
                }
            }
        } catch (Exception e) {
            log.debug("read env failed: {}", e.getMessage());
        }
        return null;
    }
}
