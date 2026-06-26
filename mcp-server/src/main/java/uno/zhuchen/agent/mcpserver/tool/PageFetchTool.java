package uno.zhuchen.agent.mcpserver.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.mcpserver.service.PageFetchService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页抓取 MCP 工具
 *
 * <p>配合 webSearch 使用：先用 webSearch 拿到搜索结果列表,
 * 再用 pageFetch 抓取感兴趣链接的正文,获取训练数据中没有的详细信息。
 *
 * <p>典型场景：
 * <ol>
 *   <li>用户问"2024年北京新开了哪些热门景点"</li>
 *   <li>LLM 调 webSearch → 拿到 10 条结果(标题+摘要+URL)</li>
 *   <li>LLM 选择最相关的 1-2 个 URL,调 pageFetch</li>
 *   <li>基于页面正文给出完整答案</li>
 * </ol>
 */
@Component
public class PageFetchTool {

    private static final Logger log = LoggerFactory.getLogger(PageFetchTool.class);

    private final PageFetchService fetchService;
    private final ObjectMapper objectMapper;

    public PageFetchTool(PageFetchService fetchService, ObjectMapper objectMapper) {
        this.fetchService = fetchService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "网页内容抓取 — 输入一个 URL,返回页面的标题和正文(纯文本)。"
            + "配合 webSearch 使用:先用 webSearch 拿到搜索结果(标题+摘要+URL),"
            + "再对感兴趣的 URL 调本工具获取详情,适合需要深度信息的场景(景点介绍/新闻详情/攻略全文等)。"
            + "不要批量抓取,一次只抓 1-2 个最相关的 URL,避免浪费 token。")
    public String pageFetch(
            @ToolParam(required = true, description = "要抓取的完整 URL,必须以 http:// 或 https:// 开头,"
                    + "通常来自 webSearch 工具返回的 url 字段") String url) {

        log.info("抓取页面: url={}", url);

        PageFetchService.PageContent result;
        try {
            result = fetchService.fetch(url);
        } catch (Exception e) {
            log.error("抓取服务异常: url={}", url, e);
            result = PageFetchService.PageContent.error(url, "抓取服务异常: " + e.getMessage());
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("url", result.url());
            body.put("title", result.title());
            body.put("content", result.content());
            body.put("rawLength", result.rawLength());
            body.put("truncated", result.truncated());
            if (result.error() != null) {
                body.put("error", result.error());
            }
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.error("页面抓取结果序列化失败", e);
            return "{\"error\":\"序列化失败\"}";
        }
    }
}
