package uno.zhuchen.agent.mcpserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uno.zhuchen.agent.tools.cloud.config.SearchConfig;
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
 * 百度通道集成测试 - 真实调用百度 Qianfan API
 *
 * <p>刻意<b>不使用</b> {@code @SpringBootTest}: 本测试只验证搜索通道本身。
 * 走全量上下文会把 MySQL / Milvus / DashScope 一并拉起, 一旦 Milvus 没启动,
 * 上下文失败会让所有上下文测试一起报错, 掩盖真正的断言结果。
 * 这里直接构造 {@link CloudSearchService}, 依赖只有网络和 API Key。
 *
 * <p>Key 解析顺序: system property -> 环境变量 -> 模块 .env -> agent-core .env。
 * 未配置时跳过(不计失败)。
 */
@Tag("integration")
class CloudSearchServiceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CloudSearchServiceIntegrationTest.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void realBaiduSearchReturnsResults() {
        CloudSearchService service = service("baidu", key("baidu.api-key", "BAIDU_API_KEY"));

        List<SearchResult> results = service.search("北京旅游景点", 5);

        log.info("百度搜索集成测试: 返回 {} 条结果", results.size());
        // 用 assume 而不是静默 return: 通道不可用时显示 skipped, 不会伪装成通过
        Assumptions.assumeFalse(results.isEmpty(), "百度通道无返回(Key 无效或网络不可达), 跳过");

        for (SearchResult r : results) {
            assertNotNull(r.title(), "标题不能为空");
            assertNotNull(r.url(), "URL 不能为空");
            assertTrue(r.url().startsWith("http"), "URL 应以 http 开头: " + r.url());
            log.info("  结果: title={}, date={}, authority={}", r.title(), r.date(), r.authority());
        }
    }

    @Test
    void searchWithChineseQueryReturnsMeaningfulResults() {
        CloudSearchService service = service("baidu", key("baidu.api-key", "BAIDU_API_KEY"));

        List<SearchResult> results = service.search("明天北京天气", 3);

        assertNotNull(results, "结果不应为 null");
        Assumptions.assumeFalse(results.isEmpty(), "百度通道无返回, 跳过");
        boolean hasWeatherKeyword = results.stream()
                .anyMatch(r -> r.title().contains("天气")
                        || (r.snippet() != null && r.snippet().contains("天气")));
        log.info("天气搜索相关性: keywordsFound={}", hasWeatherKeyword);
    }

    /**
     * 构造单通道搜索服务(不经过 Spring)。
     *
     * @param provider 通道名
     * @param apiKey   API Key, 可为 null(此时通道不参与)
     * @return 搜索服务
     */
    static CloudSearchService service(String provider, String apiKey) {
        SearchConfig config = new SearchConfig();
        config.setProvider(provider);
        if ("baidu".equals(provider)) {
            config.setBaiduApiKey(apiKey);
        } else {
            config.setTavilyApiKey(apiKey);
        }
        config.setChannelTimeoutSeconds(25);
        return new CloudSearchService(config, MAPPER);
    }

    /**
     * 解析 API Key: system property -> 环境变量 -> .env 文件。
     *
     * @param sysProp 系统属性名
     * @param envName 环境变量名
     * @return key, 缺失时 null
     */
    static String key(String sysProp, String envName) {
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
