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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索通道集成测试 - 真实调用百度 Qianfan 与 DeepSeek Search
 *
 * <p>验证三件事(前两件需要网络与 Key):
 * <ol>
 *   <li>双通道并行能正常出结果, 且融合后的 URL 不重复</li>
 *   <li><b>单路故障时整体仍可用</b>: 故意把 DeepSeek 指向一个必然连不上的地址,
 *       断言百度结果照常返回且 outcome 被标记为降级</li>
 *   <li>空 query 安全返回空结果(离线可跑)</li>
 * </ol>
 *
 * <p>Key 解析顺序: system property -> 环境变量 -> 模块 .env -> agent-core .env.
 * 缺失时通过 {@link Assumptions} 跳过, 不会让构建失败.
 *
 * <p>运行: {@code mvn -pl mcp-server test -Dtest=SearchChannelIntegrationTest -Dgroups=integration}
 */
@Tag("integration")
class SearchChannelIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SearchChannelIntegrationTest.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 必然连不上的地址, 用于模拟通道故障 */
    private static final String DEAD_BASE_URL = "http://127.0.0.1:1";

    /** 降级用例用的 query */
    private static final String DEGRADE_QUERY = "12306 学生票 规则 优惠";

    /**
     * 构造指定 provider 的搜索服务(不经过 Spring)。
     *
     * @param provider    并行策略或通道名
     * @param baiduKey    百度 Key, 可为 null
     * @param deepseekKey DeepSeek Key, 可为 null(建 parallel 时可传假 key 制造故障)
     * @param dsBaseUrl   DeepSeek Base URL, null 用默认
     * @param guardSec    通道守卫超时(秒)
     * @return 搜索服务
     */
    private static CloudSearchService service(String provider, String baiduKey, String deepseekKey,
                                              String dsBaseUrl, int guardSec) {
        SearchConfig config = new SearchConfig();
        config.setProvider(provider);
        config.setBaiduApiKey(baiduKey);
        config.setDeepseekApiKey(deepseekKey);
        if (dsBaseUrl != null) {
            config.setDeepseekBaseUrl(dsBaseUrl);
        }
        config.setChannelTimeoutSeconds(guardSec);
        return new CloudSearchService(config, MAPPER);
    }

    /**
     * 通道是否健康(未抛错)。注意: 健康不代表有结果, 只是这次调用本身没失败。
     *
     * @param outcome 搜索结果
     * @param channel 通道名
     * @return true 表示该通道本次执行成功
     */
    private static boolean channelOk(SearchOutcome outcome, String channel) {
        return outcome.channels().stream()
                .filter(c -> c.name().equals(channel))
                .findFirst()
                .map(ChannelStatus::ok)
                .orElse(false);
    }

    private static void printChannels(SearchOutcome outcome) {
        for (ChannelStatus status : outcome.channels()) {
            log.info("通道状态: name={}, hits={}, latencyMs={}, error={}",
                    status.name(), status.hits(), status.latencyMs(), status.error());
        }
    }

    @Test
    @DisplayName("双通道并行: 两路都出结果, 融合后 URL 不重复")
    void parallelSearchUsesBothChannels() {
        String baiduKey = key("baidu.api-key", "BAIDU_API_KEY");
        String deepseekKey = key("deepseek.api-key", "DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(baiduKey != null && deepseekKey != null,
                "需要同时配置 BAIDU_API_KEY 与 DEEPSEEK_API_KEY");

        SearchOutcome outcome = service("parallel", baiduKey, deepseekKey, null, 25)
                .searchDetailed("西湖景区 最新 开放时间 预约", 8);

        assertNotNull(outcome);
        printChannels(outcome);

        // 双路断言只在两路都健康时有意义: 第三方账户欠费/限流/网络不通都会让某一路返回 0 条,
        // 那不是本测试要验证的行为, 应跳过而不是失败
        Assumptions.assumeTrue(channelOk(outcome, "baidu") && channelOk(outcome, "deepseek"),
                "有通道当前不可用, 跳过双路断言: " + outcome.failures());

        assertFalse(outcome.results().isEmpty(), "双通道并行应至少有一路出结果");
        assertTrue(outcome.results().size() <= 8, "不得超过 limit");

        List<String> urls = outcome.results().stream().map(SearchResult::url).toList();
        assertTrue(urls.size() == urls.stream().distinct().count(), "融合后 URL 必须去重");

        log.info("融合结果 {} 条, 贡献通道={}, 降级={}",
                outcome.results().size(), outcome.contributedChannels(), outcome.degraded());
    }

    @Test
    @DisplayName("单路故障: DeepSeek 连不上时, 百度结果照常返回且标记降级")
    void deepseekFailureFallsBackToBaidu() {
        String baiduKey = key("baidu.api-key", "BAIDU_API_KEY");
        Assumptions.assumeTrue(baiduKey != null, "需要 BAIDU_API_KEY 才能验证降级路径");

        // 先确认百度通道本身可用: 若它自己欠费/限流, 就无从验证"DeepSeek 故障不影响百度"
        SearchOutcome baiduOnly = service("baidu", baiduKey, null, null, 25)
                .searchDetailed(DEGRADE_QUERY, 5);
        printChannels(baiduOnly);
        Assumptions.assumeTrue(channelOk(baiduOnly, "baidu"),
                "百度通道当前不可用(欠费/限流/网络), 无法验证降级: " + baiduOnly.failures());

        // 百度好 + DeepSeek 坏(假 key + 死地址)
        SearchOutcome outcome = service("parallel", baiduKey,
                "sk-invalid-for-degradation-test", DEAD_BASE_URL, 25)
                .searchDetailed(DEGRADE_QUERY, 5);

        assertNotNull(outcome);
        assertFalse(outcome.results().isEmpty(), "DeepSeek 故障不应影响百度通道返回结果");
        assertTrue(outcome.degraded(), "存在故障通道时必须标记降级");
        assertTrue(outcome.failures().contains("deepseek"), "故障原因应可读: " + outcome.failures());
        assertTrue(outcome.contributedChannels().contains("baidu"));
        assertFalse(channelOk(outcome, "deepseek"), "故障通道不应被当成健康通道");

        log.info("降级验证通过: 结果={} 条, 失败={}", outcome.results().size(), outcome.failures());
    }

    @Test
    @DisplayName("空 query 直接返回空结果, 不发起任何请求")
    void blankQueryShortCircuits() {
        CloudSearchService service = service("parallel", "unused", "unused", null, 8);

        assertTrue(service.searchDetailed("   ", 5).results().isEmpty());
        assertTrue(service.search("", 5).isEmpty());
    }

    /**
     * 解析 Keys: system property -> 环境变量 -> .env 文件.
     */
    private static String key(String sysProp, String envName) {
        String v = System.getProperty(sysProp);
        if (v != null && !v.isBlank()) {
            return v;
        }
        v = System.getenv(envName);
        if (v != null && !v.isBlank()) {
            return v;
        }
        String[] files = {
                "src/main/resources/.env",
                "../agent-core/src/main/resources/.env"
        };
        for (String f : files) {
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
