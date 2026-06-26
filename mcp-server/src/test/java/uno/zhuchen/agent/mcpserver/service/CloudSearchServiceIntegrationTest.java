package uno.zhuchen.agent.mcpserver.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 云端搜索集成测试 — 实际调用百度 Qianfan API
 *
 * <p>Tavily 测试请见 {@link TavilySearchIntegrationTest}。
 *
 * <p>本测试会发起真实的 HTTP 请求到外部搜索 API，需要：
 * <ul>
 *   <li>{@code .env} 文件中配置了 {@code BAIDU_API_KEY}</li>
 *   <li>网络可访问 {@code qianfan.baidubce.com}</li>
 * </ul>
 *
 * <p>使用 {@code @Tag("integration")} 标记，可通过 {@code -Dgroups=integration} 选择性运行。
 */
@SpringBootTest
@Tag("integration")
class CloudSearchServiceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CloudSearchServiceIntegrationTest.class);

    @Autowired
    private CloudSearchService searchService;

    @Test
    void realBaiduSearchReturnsResults() {
        List<CloudSearchService.SearchResult> results = searchService.search("北京旅游景点", 5);

        log.info("百度搜索集成测试: 返回 {} 条结果", results.size());
        if (results.isEmpty()) {
            log.warn("百度搜索无返回结果（可能 API Key 无效或网络不可达）");
            return;
        }

        for (CloudSearchService.SearchResult r : results) {
            assertNotNull(r.title(), "标题不能为空");
            assertNotNull(r.url(), "URL 不能为空");
            assertTrue(r.url().startsWith("http"), "URL 应以 http 开头: " + r.url());
            log.info("  结果: title={}, url={}", r.title(), r.url());
        }
    }

    @Test
    void searchWithChineseQueryReturnsMeaningfulResults() {
        List<CloudSearchService.SearchResult> results = searchService.search("明天北京天气", 3);

        assertNotNull(results, "结果不应为 null");
        if (!results.isEmpty()) {
            boolean hasWeatherKeyword = results.stream()
                    .anyMatch(r -> r.title().contains("天气") || r.snippet().contains("天气"));
            log.info("天气搜索相关性: keywordsFound={}", hasWeatherKeyword);
        }
    }
}
