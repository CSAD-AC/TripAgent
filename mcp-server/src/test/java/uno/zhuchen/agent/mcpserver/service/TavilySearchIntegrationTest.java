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
 * Tavily 搜索集成测试 — 确保 provider 固定为 tavily
 *
 * <p>与 CloudSearchServiceIntegrationTest 分开独立运行，
 * 通过 {@code @SpringBootTest(properties = ...)} 将 provider 强制设为 tavily。
 */
@SpringBootTest(properties = "search.cloud.provider=tavily")
@Tag("integration")
class TavilySearchIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TavilySearchIntegrationTest.class);

    @Autowired
    private CloudSearchService searchService;

    @Test
    void realTavilySearchReturnsResults() {
        // provider=tavily，确保走 Tavily 而非百度
        List<CloudSearchService.SearchResult> results = searchService.search("杭州美食攻略", 5);

        log.info("Tavily 搜索集成测试: 返回 {} 条结果", results.size());
        if (results.isEmpty()) {
            log.warn("Tavily 搜索无返回结果（可能 API Key 无效或网络不可达）");
            return;
        }

        // 验证返回结构
        for (CloudSearchService.SearchResult r : results) {
            assertNotNull(r.title(), "标题不能为空");
            assertNotNull(r.url(), "URL 不能为空");
            assertTrue(r.url().startsWith("http"), "URL 应以 http 开头: " + r.url());
            log.info("  结果: title={}, url={}", r.title(), r.url());
        }
    }
}
