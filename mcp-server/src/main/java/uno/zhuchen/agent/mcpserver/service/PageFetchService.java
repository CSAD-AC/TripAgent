package uno.zhuchen.agent.mcpserver.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 页面内容抓取服务
 *
 * <p>职责：输入 URL → 下载 HTML → 提取正文文本 → 返回结构化结果。
 * <p>配合 webSearch 使用：搜索拿到链接后,LLM 调用 pageFetch 获取感兴趣页面的详情。
 *
 * <p>实现要点：
 * <ul>
 *   <li>用 JSoup.connect() 替代 WebClient：JSoup 自带 redirect/cookie/encoding 处理,一行代码搞定</li>
 *   <li>清理噪音：移除 script/style/nav/header/footer/aside 等</li>
 *   <li>正文定位：优先 article/main/#content/.content,降级到 body</li>
 *   <li>长度截断：超过 maxContentLength 截断,防 LLM token 爆炸</li>
 *   <li>失败容错：网络/反爬/解析错误时返回空结果,不抛异常</li>
 * </ul>
 */
@Service
public class PageFetchService {

    private static final Logger log = LoggerFactory.getLogger(PageFetchService.class);

    // 关键:模拟真实浏览器 UA,部分网站(如知乎)对非浏览器 UA 直接 403
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Value("${fetch.timeout-seconds:15}")
    private int timeoutSeconds;

    @Value("${fetch.max-content-length:5000}")
    private int maxContentLength;

    /**
     * 抓取页面内容
     *
     * @param url 完整 URL,必须以 http:// 或 https:// 开头
     * @return 抓取结果(永远非 null,失败时 content 为错误信息)
     */
    public PageContent fetch(String url) {
        if (url == null || url.isBlank()) {
            return PageContent.error(url, "URL 不能为空");
        }
        if (!isValidHttpUrl(url)) {
            return PageContent.error(url, "URL 格式不合法,需要 http:// 或 https:// 开头");
        }

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(timeoutSeconds * 1000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)  // 404/500 也返回 Document,不要抛
                    .get();

            int statusCode = doc.connection().response().statusCode();
            if (statusCode >= 400) {
                log.warn("页面 HTTP {}: url={}", statusCode, url);
                return PageContent.error(url, "HTTP " + statusCode);
            }

            // 1. 清理噪音标签
            doc.select("script, style, noscript, nav, header, footer, aside, "
                    + ".nav, .header, .footer, .sidebar, .advertisement, .ad, "
                    + "[role=navigation], [role=banner], [role=contentinfo]").remove();

            // 2. 定位正文容器(优先级:article > main > #content > .content > body)
            Element contentEl = doc.selectFirst("article, main, #content, .content, .article, .post-content, body");
            if (contentEl == null) {
                contentEl = doc.body();
            }

            // 3. 提取文本(JSoup.text() 会自动去除多余空白)
            String rawText = contentEl.text();

            // 4. 截断
            String text = rawText;
            boolean truncated = false;
            if (text.length() > maxContentLength) {
                text = text.substring(0, maxContentLength);
                truncated = true;
            }

            String title = doc.title();
            log.info("页面抓取成功: url={}, title={}, 原始长度={}, 返回长度={}, 截断={}",
                    url, title, rawText.length(), text.length(), truncated);

            return new PageContent(title, text, url, rawText.length(), truncated, null);
        } catch (Exception e) {
            log.error("页面抓取失败: url={}, err={}", url, e.getMessage());
            return PageContent.error(url, "抓取失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 验证 URL 合法性
     */
    private boolean isValidHttpUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * 抓取结果
     *
     * @param title       页面标题
     * @param content     正文文本(已截断)
     * @param url         原始 URL
     * @param rawLength   原始正文长度(截断前)
     * @param truncated   是否被截断
     * @param error       错误信息(null 表示成功)
     */
    public record PageContent(String title, String content, String url,
                              int rawLength, boolean truncated, String error) {

        /** 失败时的快捷构造 */
        public static PageContent error(String url, String errorMsg) {
            return new PageContent("", "抓取失败: " + errorMsg, url, 0, false, errorMsg);
        }
    }
}
