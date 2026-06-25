package uno.zhuchen.agent.mcpserver.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 必应搜索服务 — 自建爬虫版
 *
 * <p>封装 Bing CN 搜索结果抓取，输出结构化列表给 LLM 消费。
 * <ul>
 *   <li>HTTP 层：WebClient（项目统一栈）</li>
 *   <li>HTML 解析：JSoup 1.17+，容错性强</li>
 *   <li>反爬对抗：严格 UA + Accept-Language + Referer</li>
 *   <li>稳定性：超时 8s，失败返回空列表（不抛异常）</li>
 * </ul>
 */
@Service
public class BingSearchService {

    private static final Logger log = LoggerFactory.getLogger(BingSearchService.class);

    // 关键：模拟真实浏览器 UA，Bing 对 UA 极其敏感
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Value("${search.bing.timeout-seconds:8}")
    private int timeoutSeconds;

    @Value("${search.bing.max-results:20}")
    private int maxResultsCap;

    private final WebClient webClient;

    public BingSearchService() {
        this.webClient = WebClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .defaultHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    /**
     * 执行 Bing 搜索
     *
     * @param query 搜索关键词
     * @param limit 返回结果上限（已被 maxResultsCap 二次截断）
     * @return 搜索结果列表；失败时返回空列表（绝不抛异常）
     */
    public List<SearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int cap = Math.min(Math.max(limit, 1), maxResultsCap);

        try {
            String html = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("cn.bing.com")
                            .path("/search")
                            .queryParam("q", query)
                            .queryParam("count", cap)
                            .build())
                    .header("Referer", "https://cn.bing.com/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .onErrorResume(e -> {
                        log.warn("Bing 搜索 HTTP 失败: query={}, err={}", query, e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            if (html == null || html.isEmpty()) {
                log.warn("Bing 搜索返回空响应: query={}", query);
                return List.of();
            }

            return parseHtml(html, cap);
        } catch (Exception e) {
            log.error("Bing 搜索异常: query={}", query, e);
            return List.of();
        }
    }

    /**
     * 解析 Bing CN 搜索结果 HTML
     *
     * <p>Bing CN 主结果容器：{@code <li class="b_algo">}
     * <p>提取字段：标题( {@code h2 a} )、URL( {@code h2 a@href} )、摘要( {@code .b_caption p} 或 {@code .b_snippet} )
     */
    private List<SearchResult> parseHtml(String html, int limit) {
        List<SearchResult> results = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // Bing CN 主结果选择器
        Elements items = doc.select("li.b_algo");
        for (Element item : items) {
            if (results.size() >= limit) {
                break;
            }

            try {
                Element titleEl = item.selectFirst("h2 a");
                if (titleEl == null) {
                    continue;
                }

                Element snippetEl = item.selectFirst(".b_caption p, .b_snippet, .b_paractl");

                String title = titleEl.text();
                String url = titleEl.absUrl("href");
                String snippet = snippetEl != null ? snippetEl.text() : "";

                if (url.isEmpty() || title.isEmpty()) {
                    continue;
                }

                results.add(new SearchResult(title, url, snippet));
            } catch (Exception e) {
                // 单条解析失败不影响整体
                log.debug("Bing 搜索单条解析失败: {}", e.getMessage());
            }
        }

        log.info("Bing 搜索解析: query=ok, 原始 {} 条, 有效 {} 条", items.size(), results.size());
        return results;
    }

    /**
     * 搜索结果 POJO
     *
     * @param title   结果标题
     * @param url     结果链接
     * @param snippet 结果摘要
     */
    public record SearchResult(String title, String url, String snippet) {}
}
