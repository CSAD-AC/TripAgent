package uno.zhuchen.agent.mcpserver.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.mcpserver.service.BingSearchService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上网搜索 MCP 工具
 *
 * <p>通过必应搜索引擎（Bing CN）检索互联网公开资料，返回结构化结果给 LLM。
 * <p>不抓取网页全文 — 仅返回搜索引擎摘要，避免 token 爆炸。
 * <p>降级策略：Bing 失败时不抛异常，返回"网络搜索暂不可用"提示，让 LLM 走知识兜底。
 */
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    /** 默认返回条数（用户决策：10） */
    private static final int DEFAULT_LIMIT = 10;
    /** 单次最大返回条数（硬上限） */
    private static final int MAX_LIMIT = 20;

    private final BingSearchService bingService;
    private final ObjectMapper objectMapper;

    public WebSearchTool(BingSearchService bingService, ObjectMapper objectMapper) {
        this.bingService = bingService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "上网搜索工具 — 通过必应搜索引擎检索互联网公开资料，返回 Top 10 条结果的标题、链接、摘要。"
            + "适用于查询实时信息(2024年后的事件)、新闻、攻略、景点详情等 LLM 训练数据中可能没有的知识。"
            + "当用户明确要求'上网查'、'搜一下'、'查最新'，或问题涉及训练截止后的内容时，强烈建议调用本工具。"
            + "**工作流**:本工具只返回摘要,如需深度信息,先调本工具拿到 URL,再对感兴趣的 1-2 个 URL 调用 pageFetch 抓取正文。")
    public String webSearch(
            @ToolParam(required = true, description = "搜索关键词，建议 2-8 个字,精准描述需求") String query,
            @ToolParam(required = false, description = "返回结果数量,默认 10,最大 20") Integer limit) {

        int n = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        log.info("搜索请求: query={}, limit={}", query, n);

        List<BingSearchService.SearchResult> results = bingService.search(query, n);

        // 降级: Bing 失败时返回友好提示,绝不抛异常
        if (results.isEmpty()) {
            log.warn("搜索无结果返回: query={}", query);
            return buildUnavailableResponse(query);
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("source", "bing.cn");
            body.put("total", results.size());
            body.put("results", results);
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.error("搜索结果序列化失败", e);
            return "{\"error\":\"搜索结果序列化失败\"}";
        }
    }

    /**
     * 网络搜索不可用时的友好响应
     */
    private String buildUnavailableResponse(String query) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("source", "bing.cn");
            body.put("total", 0);
            body.put("results", List.of());
            body.put("message", "网络搜索暂不可用,请基于已有知识回答,或建议用户换个问法");
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"搜索响应构造失败\"}";
        }
    }
}
