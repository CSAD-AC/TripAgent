package uno.zhuchen.agent.tools.cloud.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.cloud.model.SearchOutcome;
import uno.zhuchen.agent.tools.cloud.model.SearchResult;
import uno.zhuchen.agent.tools.cloud.service.CloudSearchService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上网搜索 MCP 工具
 *
 * <p>通过云端搜索引擎(百度 Qianfan + DeepSeek Search 并行, RRF 融合)检索互联网公开资料。
 * <p>不抓取网页全文 - 仅返回搜索引擎摘要, 避免 token 爆炸。
 * <p>降级策略: 任一路搜索通道故障/未配置都会被弃用, 只要还有一路活着就正常返回;
 * 全部失败时返回友好提示, 工具层任何路径都不抛异常。
 */
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    /** 默认返回条数 */
    private static final int DEFAULT_LIMIT = 10;
    /** 单次最大返回条数(硬上限) */
    private static final int MAX_LIMIT = 20;
    /** 单条摘要最大字符数(按码点计), 超长截断防 token 爆炸 */
    private static final int MAX_SNIPPET = 400;

    private final CloudSearchService searchService;
    private final ObjectMapper objectMapper;

    public WebSearchTool(CloudSearchService searchService, ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "上网搜索工具: 通过云端搜索引擎检索互联网公开资料, 返回 Top 10 条结果. "
            + "每条含 title/url/snippet, 并尽可能附带 date(发布日期, yyyy-MM-dd), site(站点名), "
            + "authority(站点权威性, 数值越高越可信) 字段. "
            + "适用于查询实时信息(2024年后的事件), 新闻, 攻略, 景点详情等 LLM 训练数据中可能没有的知识. "
            + "当用户明确要求'上网查', '搜一下', '查最新', 或问题涉及训练截止后的内容时, 强烈建议调用本工具. "
            + "**使用要点**: 回答票价/开放时间/政策这类时间敏感信息时必须带上 date 说明信息截至日期; "
            + "优先采信 authority 高的结果; 标题含'旅行社TOP10''纯玩团''口碑榜单'且无官方来源的多为营销软文, "
            + "不可当作事实依据. "
            + "**工作流**: 本工具只返回摘要, 如需深度信息, 先调本工具拿到 URL, "
            + "再对感兴趣的 1-2 个 URL 调用 pageFetch 抓取正文.")
    public String webSearch(
            @ToolParam(required = true, description = "搜索关键词, 建议 2-8 个字, 精准描述需求") String query,
            @ToolParam(required = false, description = "返回结果数量, 默认 10, 最大 20") Integer limit) {

        int n = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        log.info("搜索请求: query={}, limit={}", query, n);

        SearchOutcome outcome;
        try {
            outcome = searchService.searchDetailed(query, n);
        } catch (Exception e) {
            // 服务层已兜底, 这里再兜一层, 保证工具永不抛异常
            log.error("搜索服务异常: query={}", query, e);
            outcome = SearchOutcome.empty();
        }

        List<SearchResult> results = outcome == null ? List.of() : outcome.results();

        // 降级: 全部通道失败时返回友好提示, 绝不抛异常
        if (results == null || results.isEmpty()) {
            log.warn("搜索无结果返回: query={}", query);
            return buildUnavailableResponse(query, outcome);
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("source", "cloud_search");
            body.put("total", results.size());
            body.put("results", toRows(results));
            // 把"哪一路挂了"告诉 LLM: 单路供数时结果分布会偏, 回答该更保守
            if (outcome.degraded()) {
                body.put("degraded", outcome.failures().isEmpty()
                        ? "某一路搜索引擎零结果, 仅用 " + outcome.contributedChannels() + " 供数"
                        : "已弃用故障通道: " + outcome.failures());
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("搜索结果序列化失败", e);
            return "{\"error\":\"搜索结果序列化失败\"}";
        }
    }

    /**
     * 把搜索结果转成喂给 LLM 的紧凑结构
     *
     * <p>只输出非空字段, 避免 null 占位浪费 token; 摘要超长截断。
     * <p>刻意不输出 rerank: 两路 rerank 量纲不同(百度恒为 1.0, DeepSeek 0~3),
     * 给 LLM 看反而会误导; 融合排序已经在服务层用 RRF 处理过。
     */
    private List<Map<String, Object>> toRows(List<SearchResult> results) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SearchResult r : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", r.title());
            row.put("url", r.url());
            if (r.siteName() != null && !r.siteName().isBlank()) {
                row.put("site", r.siteName());
            }
            if (r.date() != null && !r.date().isBlank()) {
                row.put("date", r.date());
            }
            if (r.authority() != null) {
                row.put("authority", r.authority());
            }
            row.put("snippet", truncate(r.snippet(), MAX_SNIPPET));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 按码点截断, 避免把代理对(emoji 等)从中间切断产生乱码。
     *
     * @param text     原文
     * @param maxCodePoints 最大码点数
     * @return 截断后的文本
     */
    private static String truncate(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= maxCodePoints) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0, maxCodePoints);
        return trimmed.substring(0, end) + "...";
    }

    /**
     * 网络搜索不可用时的友好响应
     *
     * <p>带上通道故障原因, 便于排障时不用翻日志就知道是哪一路挂了。
     */
    private String buildUnavailableResponse(String query, SearchOutcome outcome) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("source", "cloud_search");
            body.put("total", 0);
            body.put("results", List.of());
            body.put("message", "网络搜索暂不可用, 请基于已有知识回答, 或建议用户换个问法");
            if (outcome != null && !outcome.channels().isEmpty()) {
                body.put("channels", outcome.channels().stream()
                        .map(c -> c.name() + (c.ok() ? ":empty" : ":fail"))
                        .toList());
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"error\":\"搜索响应构造失败\"}";
        }
    }
}
