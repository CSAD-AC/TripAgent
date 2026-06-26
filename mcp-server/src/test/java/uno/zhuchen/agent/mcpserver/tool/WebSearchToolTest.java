package uno.zhuchen.agent.mcpserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.mcpserver.service.CloudSearchService;
import uno.zhuchen.agent.mcpserver.service.CloudSearchService.SearchResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 搜索工具单元测试
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>正常搜索返回结果</li>
 *   <li>搜索无结果（空列表）→ 降级提示</li>
 *   <li>云端搜索服务抛异常 → 降级提示</li>
 *   <li>limit 参数边界：null / 负值 / 超大值</li>
 *   <li>query 为空 → 空结果降级</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WebSearchToolTest {

    @Mock
    private CloudSearchService searchService;

    private ObjectMapper objectMapper;
    private WebSearchTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new WebSearchTool(searchService, objectMapper);
    }

    @Test
    void normalSearchReturnsStructuredJson() throws Exception {
        // 准备：模拟云端搜索返回 2 条结果
        List<SearchResult> mockResults = List.of(
                new SearchResult("北京景点推荐", "https://example.com/beijing", "北京必去景点汇总"),
                new SearchResult("北京三日游", "https://example.com/3days", "经典路线推荐")
        );
        when(searchService.search("北京景点", 10)).thenReturn(mockResults);

        // 执行
        String json = tool.webSearch("北京景点", null);

        // 验证：JSON 包含预期字段
        assertNotNull(json);
        assertTrue(json.contains("\"query\":\"北京景点\""));
        assertTrue(json.contains("\"source\":\"cloud_search\""));
        assertTrue(json.contains("\"total\":2"));
        assertTrue(json.contains("北京景点推荐"));
        assertTrue(json.contains("北京三日游"));

        // 验证：limit 默认值为 10
        verify(searchService).search("北京景点", 10);
    }

    @Test
    void customLimitIsPassedToService() {
        when(searchService.search("test", 5)).thenReturn(List.of(
                new SearchResult("r1", "https://x.com/1", "s1")
        ));

        tool.webSearch("test", 5);

        verify(searchService).search("test", 5);
    }

    @Test
    void limitClampedToMax20() {
        when(searchService.search("test", 20)).thenReturn(List.of(
                new SearchResult("r1", "https://x.com/1", "s1")
        ));

        tool.webSearch("test", 999);

        verify(searchService).search("test", 20);
    }

    @Test
    void limitAtLeast1() {
        when(searchService.search("test", 1)).thenReturn(List.of(
                new SearchResult("r1", "https://x.com/1", "s1")
        ));

        // 负数应被 clamp 到 1
        tool.webSearch("test", -5);

        verify(searchService).search("test", 1);
    }

    @Test
    void emptyResultsReturnsUnavailableMessage() throws Exception {
        when(searchService.search("未知查询", 10)).thenReturn(List.of());

        String json = tool.webSearch("未知查询", null);

        assertNotNull(json);
        assertTrue(json.contains("\"total\":0"));
        assertTrue(json.contains("网络搜索暂不可用"));
    }

    @Test
    void serviceExceptionReturnsUnavailableMessage() throws Exception {
        when(searchService.search(anyString(), anyInt()))
                .thenThrow(new RuntimeException("Cloud search timeout"));

        // 工具不应抛出异常，应返回降级提示
        String json = tool.webSearch("test", null);

        assertNotNull(json);
        assertTrue(json.contains("\"total\":0"));
        assertTrue(json.contains("网络搜索暂不可用"));
    }

    @Test
    void nullQueryReturnsEmpty() throws Exception {
        // null query 应该被 service 层处理为空列表
        when(searchService.search(null, 10)).thenReturn(List.of());

        String json = tool.webSearch(null, null);

        assertNotNull(json);
        assertTrue(json.contains("\"total\":0"));
    }

    @Test
    void blankQueryReturnsEmpty() throws Exception {
        when(searchService.search("   ", 10)).thenReturn(List.of());

        String json = tool.webSearch("   ", null);

        assertNotNull(json);
        assertTrue(json.contains("\"total\":0"));
    }

    @Test
    void resultJsonCanBeParsedByObjectMapper() throws Exception {
        List<SearchResult> results = List.of(
                new SearchResult("Title", "https://url.com", "Snippet")
        );
        when(searchService.search("t", 10)).thenReturn(results);

        String json = tool.webSearch("t", null);

        // 反序列化验证 JSON 格式正确
        var parsed = objectMapper.readTree(json);
        assertEquals("t", parsed.get("query").asText());
        assertEquals(1, parsed.get("total").asInt());
        assertTrue(parsed.has("results"));
        assertEquals("Title", parsed.get("results").get(0).get("title").asText());
        assertEquals("Snippet", parsed.get("results").get(0).get("snippet").asText());
    }

    @Test
    void serviceExceptionNeverThrows() {
        // 服务异常应被工具层 try-catch 兜住
        doThrow(new RuntimeException("search failed")).when(searchService).search(anyString(), anyInt());
        assertDoesNotThrow(() -> tool.webSearch("crash", null));
    }

    @Test
    void nullResultNeverThrows() {
        // null 返回值也应安全处理
        doReturn(null).when(searchService).search(anyString(), anyInt());
        assertDoesNotThrow(() -> tool.webSearch("nullResult", null));
    }
}
