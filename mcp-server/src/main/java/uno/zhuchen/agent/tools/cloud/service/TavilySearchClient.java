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
 * Tavily 通道
 *
 * <p>专为 AI 设计的搜索引擎 API, 作为百度/DeepSeek 双通道都不可用时的最后备选。
 * 只提供 score(0~1 相关度), 没有发布时间与权威性, 因此不进入默认并行通道。
 */
class TavilySearchClient implements SearchClient {

    private static final Logger log = LoggerFactory.getLogger(TavilySearchClient.class);

    /** Tavily Search API 地址 */
    private static final String API_URL = "https://api.tavily.com/search";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final int timeoutSeconds;

    TavilySearchClient(WebClient webClient, ObjectMapper objectMapper,
                       String apiKey, int timeoutSeconds) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 执行 Tavily 搜索。
     *
     * @param query 搜索词
     * @param limit 期望返回条数
     * @return 结果列表, 可能为空
     * @throws SearchChannelException 网络/协议错误
     */
    @Override
    public List<SearchResult> search(String query, int limit) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SearchChannelException("api key missing");
        }

        String requestBody = buildRequestBody(query);
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

    private String buildRequestBody(String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("search_depth", "advanced");
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new SearchChannelException("request build failed: " + e.getMessage(), e);
        }
    }

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
            raw.add(new SearchResult(
                    title,
                    url,
                    SearchText.textOf(item, "content").trim(),
                    null,
                    SearchText.normalizeDate(SearchText.textOf(item, "published_date")),
                    null,
                    SearchText.doubleOf(item, "score")));
        }

        List<SearchResult> deduped = SearchText.dedupe(raw, limit);
        log.debug("tavily parsed: raw={}, after dedupe={}", raw.size(), deduped.size());
        return deduped;
    }
}
