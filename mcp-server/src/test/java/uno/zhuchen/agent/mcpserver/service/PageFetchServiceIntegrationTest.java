package uno.zhuchen.agent.mcpserver.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 页面抓取集成测试 — 实际 HTTP 请求到外部网站
 *
 * <p>本测试会发起真实的 HTTP GET 请求到互联网上的公开页面，需要网络可达。
 *
 * <p>使用 {@code @Tag("integration")} 标记，可通过 {@code -Dgroups=integration} 选择性运行。
 */
@SpringBootTest
@Tag("integration")
class PageFetchServiceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PageFetchServiceIntegrationTest.class);

    @Autowired
    private PageFetchService fetchService;

    @Test
    void fetchRealWebPageReturnsContent() {
        // 抓取 example.com — 这是一个永远可用的测试页面
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
        // 抓取一个中文页面
        PageFetchService.PageContent result = fetchService.fetch("https://www.baidu.com");

        assertNotNull(result, "结果不应为 null");
        // 百度可能对非浏览器 UA 有限制，所以允许 error
        if (result.error() != null) {
            log.warn("百度页面抓取受限: {}", result.error());
            return;
        }
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
        if (result.error() != null) {
            log.warn("httpbin 不可达: {}", result.error());
            return;
        }
        assertNotNull(result.title(), "标题不应为 null");
        assertTrue(result.content().length() > 0, "内容不应为空");
        log.info("httpbin 抓取成功: title={}, rawLength={}", result.title(), result.rawLength());
    }
}
