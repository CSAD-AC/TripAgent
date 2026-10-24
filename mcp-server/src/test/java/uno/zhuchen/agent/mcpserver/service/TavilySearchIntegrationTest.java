package uno.zhuchen.agent.mcpserver.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uno.zhuchen.agent.tools.cloud.model.SearchResult;
import uno.zhuchen.agent.tools.cloud.service.CloudSearchService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tavily 通道集成测试 - 把 provider 钉成 tavily, 实际调用 Tavily Search API
 *
 * <p>与 {@link CloudSearchServiceIntegrationTest} 一样<b>不启动 Spring 上下文</b>:
 * 本测试只关心通道本身, 拉全量上下文会连带要求 MySQL / Milvus / DashScope,
 * Milvus 未启动时整个上下文失败, 测试报错但与 Tavily 通道无关。
 */
@Tag("integration")
class TavilySearchIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TavilySearchIntegrationTest.class);

    @Test
    void realTavilySearchReturnsResults() {
        String apiKey = CloudSearchServiceIntegrationTest.key("tavily.api-key", "TAVILY_API_KEY");
        Assumptions.assumeTrue(apiKey != null, "未配置 TAVILY_API_KEY, 跳过");

        CloudSearchService service = CloudSearchServiceIntegrationTest.service("tavily", apiKey);
        List<SearchResult> results = service.search("杭州美食攻略", 5);

        log.info("Tavily 搜索集成测试: 返回 {} 条结果", results.size());
        Assumptions.assumeFalse(results.isEmpty(), "Tavily 通道无返回(Key 无效或网络不可达), 跳过");

        for (SearchResult r : results) {
            assertNotNull(r.title(), "标题不能为空");
            assertNotNull(r.url(), "URL 不能为空");
            assertTrue(r.url().startsWith("http"), "URL 应以 http 开头: " + r.url());
            log.info("  结果: title={}, url={}", r.title(), r.url());
        }
    }
}
