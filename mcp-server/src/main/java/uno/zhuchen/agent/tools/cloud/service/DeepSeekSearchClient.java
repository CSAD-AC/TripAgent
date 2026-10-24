package uno.zhuchen.agent.tools.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import uno.zhuchen.agent.tools.cloud.model.SearchResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek Search 通道
 *
 * <p>接口: {@code POST {baseUrl}/v1/tools/search}, Bearer 鉴权, 由 DeepSeek 自研搜索引擎提供。
 * 相比百度通道的优势(实测): {@code authority}(0~4) 与 {@code rerank_score}(0~3) 有真实梯度,
 * 官方来源(12306 / 政府站 / 部委官网)能排到前排; 支持 {@code site:} / {@code after:} 等搜索语法。
 *
 * <p>已知短板: 大陆票务/价格类问题会大量返回港台繁体页与外汇报价, 且 {@code localization}
 * 与 {@code lang:zh} 实测均无法纠正 —— 这也是保留百度通道并行、而不是单通道的主要理由。
 *
 * <p>本通道把长摘要 {@code summary}(600~800 字) 优先作为 snippet, 信息密度显著高于
 * {@code snippet}(100~200 字); 短摘要仅在长摘要缺失时使用。
 */
class DeepSeekSearchClient implements SearchClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekSearchClient.class);

    /** 搜索接口路径 */
    private static final String SEARCH_PATH = "/v1/tools/search";

    /** count 上限 */
    private static final int MAX_COUNT = 50;

    /** 可重试的等待时长(毫秒), 文档建议遇到 408/429/5xx 等待后重试 */
    private static final long RETRY_BACKOFF_MS = 1000L;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final int timeoutSeconds;

    DeepSeekSearchClient(WebClient webClient, ObjectMapper objectMapper,
                         String apiKey, String baseUrl, int timeoutSeconds) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 执行 DeepSeek 搜索。
     *
     * @param query 搜索词
     * @param limit 期望返回条数
     * @return 结果列表, 可能为空
     * @throws SearchChannelException 网络/协议/业务错误(已经重试过可重试的错误码)
     */
    @Override
    public List<SearchResult> search(String query, int limit) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SearchChannelException("api key missing");
        }
        if (baseUrl.isBlank()) {
            throw new SearchChannelException("base url missing");
        }

        String requestBody = buildRequestBody(query, limit);
        String response = postWithRetry(requestBody);
        return parse(response, limit);
    }

    /**
     * 发送请求; 408/429/5xx 按文档重试一次, 其它错误直接抛。
     */
    private String postWithRetry(String requestBody) {
        for (int attempt = 0; ; attempt++) {
            try {
                String response = webClient.post()
                        .uri(baseUrl + SEARCH_PATH)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .onErrorResume(e -> Mono.error(SearchChannelException.from(e)))
                        .block();
                if (response == null || response.isBlank()) {
                    throw new SearchChannelException("empty response");
                }
                return response;
            } catch (SearchChannelException e) {
                if (attempt == 0 && retryable(e)) {
                    log.warn("deepseek 可重试错误, 1s 后重试一次: {}", e.getMessage());
                    sleep();
                    continue;
                }
                throw e;
            }
        }
    }

    /**
     * 判断是否为可重试错误(HTTP 408 / 429 / 5xx)。
     */
    private static boolean retryable(SearchChannelException e) {
        Throwable cause = e.getCause();
        if (cause instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 408 || status == 429 || status >= 500;
        }
        return false;
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_BACKOFF_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 构建请求体; 多取一倍给跨通道去重留填充空间。
     */
    private String buildRequestBody(String query, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("count", Math.min(Math.max(limit, 1) * 2, MAX_COUNT));
        // 正文按需取: 默认关闭, 与百度通道口径保持一致, 避免单条灌入过多 token
        body.put("need_content", false);
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

        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            return List.of();
        }

        List<SearchResult> raw = new ArrayList<>();
        for (JsonNode item : results) {
            String title = SearchText.textOf(item, "title").trim();
            String url = SearchText.textOf(item, "url").trim();
            if (title.isEmpty() || url.isEmpty()) {
                continue;
            }
            // 长摘要优先: 600~800 字的 summary 信息密度远高于 100~200 字的 snippet
            String content = SearchText.textOf(item, "summary").trim();
            if (content.isEmpty()) {
                content = SearchText.textOf(item, "snippet").trim();
            }
            raw.add(new SearchResult(
                    title,
                    url,
                    content,
                    emptyToNull(SearchText.textOf(item, "site_name")),
                    SearchText.normalizeDate(SearchText.textOf(item, "date")),
                    SearchText.doubleOf(item, "authority"),
                    SearchText.doubleOf(item, "rerank_score")));
        }

        List<SearchResult> deduped = SearchText.dedupe(raw, limit);
        log.debug("deepseek parsed: raw={}, after dedupe={}", raw.size(), deduped.size());
        return deduped;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
