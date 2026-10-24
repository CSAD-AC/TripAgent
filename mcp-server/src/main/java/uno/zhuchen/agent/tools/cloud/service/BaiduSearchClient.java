package uno.zhuchen.agent.tools.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import uno.zhuchen.agent.tools.cloud.model.SearchResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百度千帆 AI Search 通道
 *
 * <p>响应单条 reference 的实测完整字段:
 * <pre>
 * [id, url, title, date, content, icon, web_anchor, type, website, video,
 *  image, is_aladdin, aladdin, snippet, web_extensions, rerank_score,
 *  authority_score, markdown_content]
 * </pre>
 * 本通道取 title / url / website / date / authority_score / rerank_score / snippet,
 * 其余丢弃。注意 {@code rerank_score} 实测恒为 1.0, 无区分力, 排序主要靠百度自身顺序。
 *
 * <p>百度索引被 SEO 软文农场污染严重(实测某攻略 query 的 Top12 里 10 条是同一篇软文),
 * 因此解析后必须过 {@link SearchText#dedupe}。
 */
class BaiduSearchClient implements SearchClient {

    private static final Logger log = LoggerFactory.getLogger(BaiduSearchClient.class);

    /** 百度千帆 AI Search API 地址 */
    private static final String API_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";

    /** 百度 top_k 上限 */
    private static final int MAX_TOP_K = 50;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final int timeoutSeconds;

    BaiduSearchClient(WebClient webClient, ObjectMapper objectMapper,
                      String apiKey, int timeoutSeconds) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 执行百度搜索。
     *
     * @param query 搜索词
     * @param limit 期望返回条数
     * @return 结果列表, 可能为空(确实没搜到)
     * @throws SearchChannelException 网络/协议/业务错误
     */
    @Override
    public List<SearchResult> search(String query, int limit) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SearchChannelException("api key missing");
        }

        String requestBody = buildRequestBody(query, limit);
        String response;
        try {
            response = webClient.post()
                    .uri(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .onErrorResume(e -> Mono.error(SearchChannelException.from(e)))
                    .block();
        } catch (SearchChannelException e) {
            throw e;
        } catch (Exception e) {
            throw SearchChannelException.from(e);
        }

        if (response == null || response.isBlank()) {
            throw new SearchChannelException("empty response");
        }
        return parse(response, limit);
    }

    /**
     * 构建请求体
     *
     * <p>多取一倍: 解析阶段会做 URL/标题/正文去重, 留出填充空间。
     */
    private String buildRequestBody(String query, int limit) {
        int topK = Math.min(Math.max(limit, 1) * 2, MAX_TOP_K);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("type", "web");
        filter.put("top_k", topK);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", query);
        message.put("role", "user");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(message));
        body.put("search_source", "baidu_search_v2");
        body.put("resource_type_filter", List.of(filter));

        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new SearchChannelException("request build failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解析响应并去重。
     */
    private List<SearchResult> parse(String json, int limit) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new SearchChannelException("response parse failed: " + e.getMessage(), e);
        }

        // 业务错误码: 非 0 视为通道故障(配额/鉴权等), 而不是"没搜到"
        String code = SearchText.textOf(root, "code");
        if (!code.isEmpty() && !"0".equals(code)) {
            throw new SearchChannelException("api code=" + code + " " + SearchText.textOf(root, "message"));
        }

        JsonNode refs = root.get("references");
        if (refs == null || !refs.isArray()) {
            return List.of();
        }

        List<SearchResult> raw = new ArrayList<>();
        for (JsonNode ref : refs) {
            String type = SearchText.textOf(ref, "type");
            // 只取网页类型; type 缺失时按 web 处理, 不整批丢弃
            if (!type.isEmpty() && !"web".equals(type)) {
                continue;
            }
            String title = SearchText.textOf(ref, "title").trim();
            String url = SearchText.textOf(ref, "url").trim();
            if (title.isEmpty() || url.isEmpty()) {
                continue;
            }
            // 摘要优先 snippet, 缺失回退 content
            String snippet = SearchText.textOf(ref, "snippet").trim();
            if (snippet.isEmpty()) {
                snippet = SearchText.textOf(ref, "content").trim();
            }
            raw.add(new SearchResult(
                    title,
                    url,
                    snippet,
                    emptyToNull(SearchText.textOf(ref, "website")),
                    SearchText.normalizeDate(SearchText.textOf(ref, "date")),
                    SearchText.doubleOf(ref, "authority_score"),
                    SearchText.doubleOf(ref, "rerank_score")));
        }

        List<SearchResult> deduped = SearchText.dedupe(raw, limit);
        log.debug("baidu parsed: raw={}, after dedupe={}", raw.size(), deduped.size());
        return deduped;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
