package uno.zhuchen.agent.mcpserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import uno.zhuchen.agent.mcpserver.config.SearchConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 云端搜索服务 — 替代自建 Bing 爬虫
 *
 * <p>支持两个后端（按配置选择）：
 * <ul>
 *   <li><b>百度 Qianfan</b> — 通过千帆 AI Search API 调用百度搜索，支持站点/时效过滤</li>
 *   <li><b>Tavily</b> — 专为 AI 设计的搜索引擎 API</li>
 * </ul>
 *
 * <p>默认策略：优先百度，失败后自动降级到 Tavily。
 * 无论后端是否可用，均不抛异常（返回空列表让调用方自行降级）。
 */
@Service
public class CloudSearchService {

    private static final Logger log = LoggerFactory.getLogger(CloudSearchService.class);

    /** 百度千帆 AI Search API 地址 */
    private static final String BAIDU_API_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    /** Tavily Search API 地址 */
    private static final String TAVILY_API_URL = "https://api.tavily.com/search";

    /** HTTP 超时（秒） */
    private static final int TIMEOUT_SECONDS = 15;

    private final SearchConfig config;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public CloudSearchService(SearchConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    /**
     * 执行云端搜索
     *
     * @param query 搜索关键词
     * @param limit 返回结果上限
     * @return 搜索结果列表；失败时返回空列表（绝不抛异常）
     */
    public List<SearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String provider = config.getProvider();

        // 根据 provider 策略选择搜索后端
        return switch (provider) {
            case "baidu" -> searchBaidu(query, limit);
            case "tavily" -> searchTavily(query, limit);
            case "baidu-tavily-fallback" -> {
                List<SearchResult> results = searchBaidu(query, limit);
                if (results.isEmpty()) {
                    log.info("百度搜索无结果/失败，降级到 Tavily: query={}", query);
                    results = searchTavily(query, limit);
                }
                yield results;
            }
            default -> {
                log.warn("未知的搜索 provider: {}，使用百度", provider);
                yield searchBaidu(query, limit);
            }
        };
    }

    // ==================== 百度 Qianfan ====================

    /**
     * 调用百度千帆 AI Search API
     */
    private List<SearchResult> searchBaidu(String query, int limit) {
        String apiKey = config.getBaiduApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("百度 API Key 未配置，跳过百度搜索");
            return List.of();
        }

        try {
            // 构建请求体
            String requestBody = buildBaiduRequestBody(query, limit);

            String response = webClient.post()
                    .uri(BAIDU_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .onErrorResume(e -> {
                        log.warn("百度搜索 HTTP 失败: query={}, err={}", query, e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            if (response == null || response.isBlank()) {
                return List.of();
            }

            return parseBaiduResponse(response, limit);
        } catch (Exception e) {
            log.error("百度搜索异常: query={}", query, e);
            return List.of();
        }
    }

    /**
     * 构建百度搜索请求体 JSON
     */
    private String buildBaiduRequestBody(String query, int limit) {
        try {
            // 限制 top_k 范围：1-50
            int topK = Math.min(Math.max(limit, 1), 50);
            return objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "messages", List.of(
                                    java.util.Map.of("content", query, "role", "user")
                            ),
                            "search_source", "baidu_search_v2",
                            "resource_type_filter", List.of(
                                    java.util.Map.of("type", "web", "top_k", topK)
                            )
                    )
            );
        } catch (Exception e) {
            log.error("构建百度请求体失败", e);
            return "{}";
        }
    }

    /**
     * 解析百度搜索响应
     *
     * <p>百度返回格式：
     * <pre>
     * {
     *   "references": [
     *     { "id": 1, "title": "...", "url": "...", "content": "...", "date": "...", "type": "web" }
     *   ],
     *   "request_id": "..."
     * }
     * </pre>
     */
    private List<SearchResult> parseBaiduResponse(String json, int limit) {
        List<SearchResult> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);

            // 检查错误码
            JsonNode codeNode = root.get("code");
            if (codeNode != null && !codeNode.asText().isEmpty() && !"0".equals(codeNode.asText())) {
                String msg = root.has("message") ? root.get("message").asText() : "未知错误";
                log.warn("百度搜索 API 返回错误: code={}, message={}", codeNode.asText(), msg);
                return List.of();
            }

            JsonNode refs = root.get("references");
            if (refs == null || !refs.isArray()) {
                return List.of();
            }

            for (JsonNode ref : refs) {
                if (results.size() >= limit) {
                    break;
                }
                try {
                    String type = ref.has("type") ? ref.get("type").asText() : "";
                    // 只取网页类型结果
                    if (!"web".equals(type)) {
                        continue;
                    }

                    String title = ref.has("title") ? ref.get("title").asText() : "";
                    String url = ref.has("url") ? ref.get("url").asText() : "";
                    String snippet = ref.has("content") ? ref.get("content").asText() : "";

                    if (title.isEmpty() || url.isEmpty()) {
                        continue;
                    }

                    results.add(new SearchResult(title, url, snippet));
                } catch (Exception e) {
                    log.debug("百度搜索单条解析失败: {}", e.getMessage());
                }
            }

            log.info("百度搜索成功: query=ok, 结果 {} 条", results.size());
        } catch (Exception e) {
            log.error("百度搜索响应解析失败", e);
        }
        return results;
    }

    // ==================== Tavily ====================

    /**
     * 调用 Tavily Search API
     */
    private List<SearchResult> searchTavily(String query, int limit) {
        String apiKey = config.getTavilyApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Tavily API Key 未配置，跳过 Tavily 搜索");
            return List.of();
        }

        try {
            String requestBody = buildTavilyRequestBody(query);

            String response = webClient.post()
                    .uri(TAVILY_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .onErrorResume(e -> {
                        log.warn("Tavily 搜索 HTTP 失败: query={}, err={}", query, e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            if (response == null || response.isBlank()) {
                return List.of();
            }

            return parseTavilyResponse(response, limit);
        } catch (Exception e) {
            log.error("Tavily 搜索异常: query={}", query, e);
            return List.of();
        }
    }

    /**
     * 构建 Tavily 搜索请求体 JSON
     */
    private String buildTavilyRequestBody(String query) {
        try {
            return objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "query", query,
                            "search_depth", "advanced"
                    )
            );
        } catch (Exception e) {
            log.error("构建 Tavily 请求体失败", e);
            return "{}";
        }
    }

    /**
     * 解析 Tavily 搜索响应
     *
     * <p>Tavily 返回格式：
     * <pre>
     * {
     *   "results": [
     *     { "title": "...", "url": "...", "content": "..." }
     *   ]
     * }
     * </pre>
     */
    private List<SearchResult> parseTavilyResponse(String json, int limit) {
        List<SearchResult> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode resultsNode = root.get("results");
            if (resultsNode == null || !resultsNode.isArray()) {
                return List.of();
            }

            for (JsonNode item : resultsNode) {
                if (results.size() >= limit) {
                    break;
                }
                try {
                    String title = item.has("title") ? item.get("title").asText() : "";
                    String url = item.has("url") ? item.get("url").asText() : "";
                    String snippet = item.has("content") ? item.get("content").asText() : "";

                    if (title.isEmpty() || url.isEmpty()) {
                        continue;
                    }

                    results.add(new SearchResult(title, url, snippet));
                } catch (Exception e) {
                    log.debug("Tavily 单条解析失败: {}", e.getMessage());
                }
            }

            log.info("Tavily 搜索成功: query=ok, 结果 {} 条", results.size());
        } catch (Exception e) {
            log.error("Tavily 搜索响应解析失败", e);
        }
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
