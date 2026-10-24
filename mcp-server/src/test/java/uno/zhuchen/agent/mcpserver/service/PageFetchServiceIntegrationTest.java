package uno.zhuchen.agent.mcpserver.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.zhuchen.agent.tools.amap.service.PageFetchService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面抓取集成测试 - 实际 HTTP 请求到外部网站
 *
 * <p>本测试会发起真实的 HTTP GET 请求到互联网上的公开页面, 需要网络可达。
 *
 * <p><b>为什么摘掉 Milvus</b>: 本测试只验证 Jsoup 抓取与正文提取, 与向量库无关。
 * 但 {@code @SpringBootTest} 会拉起全量上下文, Milvus 未启动时整上下文失败、测试被连坐。
 * 这里排除 Milvus 自动配置并用 mock 补上 {@code VectorStore}, 使本测试不依赖 Milvus。
 *
 * <p>使用 {@code @Tag("integration")} 标记, 可通过 {@code -Dgroups=integration} 选择性运行。
 */
@SpringBootTest(properties = {
        "rag.seed.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration"
})
@Tag("integration")
class PageFetchServiceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PageFetchServiceIntegrationTest.class);

    @Autowired
    private PageFetchService fetchService;

    /** 替代真实 Milvus 向量库, 只为满足 IngestionService 的构造依赖 */
    @MockitoBean
    private VectorStore vectorStore;

    @Test
    void fetchRealWebPageReturnsContent() {
        PageFetchService.PageContent result = fetchService.fetch("https://example.com");

        assertNotNull(result, "结果不应为 null");
        assertNull(result.error(), "example.com 不应返回错误: " + result.error());
        assertNotNull(result.title(), "标题不应为 null");
        assertNotNull(result.content(), "内容不应为 null");
        assertTrue(result.content().length() > 0, "内容不应为空");
        assertTrue(result.rawLength() > 0, "原始长度应大于 0");

        log.info("页面抓取成功: title={}, rawLength={}, truncated={}",
                result.title(), result.rawLength(), result.truncated());
    }

    @Test
    void fetchRealChineseWebPageReturnsContent() {
        PageFetchService.PageContent result = fetchService.fetch("https://www.baidu.com");

        assertNotNull(result, "结果不应为 null");
        // 百度可能对非浏览器 UA 有限制, 允许 error 但需记录
        Assumptions.assumeTrue(result.error() == null, "百度页面抓取受限, 跳过: " + result.error());
        assertNotNull(result.title(), "标题不应为 null");
        assertTrue(result.content().length() > 0, "内容不应为空");
        log.info("百度页面抓取成功: title={}, rawLength={}", result.title(), result.rawLength());
    }

    @Test
    void fetchInvalidUrlReturnsError() {
        PageFetchService.PageContent result = fetchService.fetch("https://this-domain-does-not-exist-12345.com");

        assertNotNull(result, "结果不应为 null");
        assertNotNull(result.error(), "不存在的域名应返回错误");
        log.info("无效 URL 测试: error={}", result.error());
    }

    @Test
    void fetchWithHttpsUrlSucceeds() {
        PageFetchService.PageContent result = fetchService.fetch("https://httpbin.org/html");

        assertNotNull(result, "结果不应为 null");
        Assumptions.assumeTrue(result.error() == null, "httpbin 不可达, 跳过: " + result.error());
        assertNotNull(result.title(), "标题不应为 null");
        assertTrue(result.content().length() > 0, "内容不应为空");
        log.info("httpbin 抓取成功: title={}, rawLength={}", result.title(), result.rawLength());
    }
}
