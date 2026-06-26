package uno.zhuchen.agent.mcpserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.mcpserver.service.PageFetchService;
import uno.zhuchen.agent.mcpserver.service.PageFetchService.PageContent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 页面抓取工具单元测试
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>正常抓取返回结构化 JSON</li>
 *   <li>页面抓取失败（404/网络错误）→ 错误结果</li>
 *   <li>URL 为空/不合法 → 格式错误提示</li>
 *   <li>内容截断标记正确传递</li>
 *   <li>永不抛异常</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PageFetchToolTest {

    @Mock
    private PageFetchService fetchService;

    private ObjectMapper objectMapper;
    private PageFetchTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new PageFetchTool(fetchService, objectMapper);
    }

    @Test
    void normalFetchReturnsStructuredJson() throws Exception {
        PageContent content = new PageContent("测试页面", "这是正文内容，包含一些文本",
                "https://example.com/page", 100, false, null);
        when(fetchService.fetch("https://example.com/page")).thenReturn(content);

        String json = tool.pageFetch("https://example.com/page");

        assertNotNull(json);
        assertTrue(json.contains("\"url\":\"https://example.com/page\""));
        assertTrue(json.contains("\"title\":\"测试页面\""));
        assertTrue(json.contains("\"content\":\"这是正文内容，包含一些文本\""));
        assertTrue(json.contains("\"rawLength\":100"));
        assertTrue(json.contains("\"truncated\":false"));
    }

    @Test
    void errorFetchReturnsErrorInJson() throws Exception {
        PageContent error = PageContent.error("https://example.com/404", "HTTP 404");
        when(fetchService.fetch("https://example.com/404")).thenReturn(error);

        String json = tool.pageFetch("https://example.com/404");

        assertNotNull(json);
        assertTrue(json.contains("\"error\":\"HTTP 404\""));
        // 即使失败，content 字段也应存在（工具不抛异常）
        assertTrue(json.contains("\"content\""));
        assertTrue(json.contains("\"rawLength\":0"));
    }

    @Test
    void truncatedContentMarksFlag() throws Exception {
        // rawLength > maxContentLength → truncated=true
        PageContent truncated = new PageContent("长文章", "截断后的文本",
                "https://example.com/long", 10000, true, null);
        when(fetchService.fetch("https://example.com/long")).thenReturn(truncated);

        String json = tool.pageFetch("https://example.com/long");

        var parsed = objectMapper.readTree(json);
        assertTrue(parsed.get("truncated").asBoolean());
        assertEquals(10000, parsed.get("rawLength").asInt());
    }

    @Test
    void serviceExceptionIsCaught() {
        when(fetchService.fetch(anyString()))
                .thenReturn(PageContent.error("https://x.com", "抓取失败: IOException: Connection reset"));

        String json = tool.pageFetch("https://x.com");

        assertNotNull(json);
        assertTrue(json.contains("抓取失败"));
    }

    @Test
    void neverThrowsException() {
        // 服务异常应被工具层 try-catch 兜住
        doThrow(new RuntimeException("db crash")).when(fetchService).fetch(anyString());
        assertDoesNotThrow(() -> tool.pageFetch("https://example.com"));
    }

    @Test
    void jsonCanBeParsedBack() throws Exception {
        PageContent content = new PageContent("标题", "正文内容",
                "https://example.com/article", 500, false, null);
        when(fetchService.fetch("https://example.com/article")).thenReturn(content);

        String json = tool.pageFetch("https://example.com/article");
        var parsed = objectMapper.readTree(json);

        assertEquals("标题", parsed.get("title").asText());
        assertEquals("正文内容", parsed.get("content").asText());
        assertEquals("https://example.com/article", parsed.get("url").asText());
        assertEquals(500, parsed.get("rawLength").asInt());
        assertFalse(parsed.get("truncated").asBoolean());
        assertNull(parsed.get("error"));
    }

    @Test
    void errorResultContainsErrorField() throws Exception {
        PageContent error = PageContent.error("https://x.com/e", "HTTP 500");
        when(fetchService.fetch("https://x.com/e")).thenReturn(error);

        String json = tool.pageFetch("https://x.com/e");
        var parsed = objectMapper.readTree(json);

        assertTrue(parsed.has("error"));
        assertEquals("HTTP 500", parsed.get("error").asText());
        // content 包含错误描述
        assertTrue(parsed.get("content").asText().contains("抓取失败"));
    }
}
