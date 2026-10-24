package uno.zhuchen.agent.mcpserver.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.tools.cloud.model.ChannelStatus;
import uno.zhuchen.agent.tools.cloud.model.SearchOutcome;
import uno.zhuchen.agent.tools.cloud.model.SearchResult;
import uno.zhuchen.agent.tools.cloud.service.CloudSearchService;
import uno.zhuchen.agent.tools.cloud.tool.WebSearchTool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索工具单元测试
 *
 * <p>覆盖场景:
 * <ul>
 *   <li>正常搜索返回结构化结果</li>
 *   <li>无结果 / 服务异常 -> 降级提示且不抛异常</li>
 *   <li>limit 参数边界: null / 负值 / 超大值</li>
 *   <li>通道降级信息透出</li>
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

    /**
     * 构造一个"所有通道都正常"的 outcome
     *
     * <p>两路条数刻意差异化: 真实场景里两路返回条数几乎不会相同,
     * 若都一样, 断言会依赖"两路条数相等"这个假象。
     */
    private static SearchOutcome healthy(List<SearchResult> results) {
        int baiduHits = results.size();
        int deepseekHits = Math.max(1, results.size() - 1);
        return new SearchOutcome(results,
                List.of(new ChannelStatus("baidu", baiduHits, 120L, null),
                        new ChannelStatus("deepseek", deepseekHits, 90L, null)));
    }

    @Test
    void normalSearchReturnsStructuredJson() throws Exception {
        List<SearchResult> mockResults = List.of(
                new SearchResult("北京景点推荐", "https://example.com/beijing", "北京必去景点汇总"),
                new SearchResult("北京三日游", "https://example.com/3days", "经典路线推荐"));
        when(searchService.searchDetailed("北京景点", 10)).thenReturn(healthy(mockResults));

        String json = tool.webSearch("北京景点", null);

        assertNotNull(json);
        assertTrue(json.contains("\"query\":\"北京景点\""));
        assertTrue(json.contains("\"source\":\"cloud_search\""));
        assertTrue(json.contains("\"total\":2"));
        assertTrue(json.contains("北京景点推荐"));
        assertTrue(json.contains("北京三日游"));
        assertFalse(json.contains("degraded"), "两路都正常时不应出现降级标记");

        verify(searchService).searchDetailed("北京景点", 10);
    }

    @Test
    void customLimitIsPassedToService() {
        when(searchService.searchDetailed("test", 5))
                .thenReturn(healthy(List.of(new SearchResult("r1", "https://x.com/1", "s1"))));

        tool.webSearch("test", 5);

        verify(searchService).searchDetailed("test", 5);
    }

    @Test
    void limitClampedToMax20() {
        when(searchService.searchDetailed("test", 20))
                .thenReturn(healthy(List.of(new SearchResult("r1", "https://x.com/1", "s1"))));

        tool.webSearch("test", 999);

        verify(searchService).searchDetailed("test", 20);
    }

    @Test
    void limitAtLeast1() {
        when(searchService.searchDetailed("test", 1))
                .thenReturn(healthy(List.of(new SearchResult("r1", "https://x.com/1", "s1"))));

        // 负数应被 clamp 到 1
        tool.webSearch("test", -5);

        verify(searchService).searchDetailed("test", 1);
    }

    @Test
    @DisplayName("某一路故障时, 结果照常返回并带降级说明")
    void degradedChannelIsReportedInPayload() throws Exception {
        List<SearchResult> results = List.of(
                new SearchResult("Title", "https://url.com", "Snippet"));
        when(searchService.searchDetailed("t", 10)).thenReturn(new SearchOutcome(results, List.of(
                new ChannelStatus("baidu", 1, 120L, null),
                new ChannelStatus("deepseek", 0, 30L, "timeout"))));

        var parsed = objectMapper.readTree(tool.webSearch("t", null));

        assertEquals(1, parsed.get("total").asInt());
        assertTrue(parsed.has("degraded"), "降级信息应透出给 LLM");
        assertTrue(parsed.get("degraded").asText().contains("deepseek"));
    }

    @Test
    void emptyResultsReturnsUnavailableMessage() throws Exception {
        when(searchService.searchDetailed("未知查询", 10))
                .thenReturn(new SearchOutcome(List.of(),
                        List.of(new ChannelStatus("baidu", 0, 100L, null))));

        String json = tool.webSearch("未知查询", null);

        assertNotNull(json);
        assertTrue(json.contains("\"total\":0"));
        assertTrue(json.contains("网络搜索暂不可用"));
    }

    @Test
    void serviceExceptionReturnsUnavailableMessage() throws Exception {
        when(searchService.searchDetailed(anyString(), anyInt()))
                .thenThrow(new RuntimeException("Cloud search timeout"));

        String json = tool.webSearch("test", null);

        assertNotNull(json);
        assertTrue(json.contains("\"total\":0"));
        assertTrue(json.contains("网络搜索暂不可用"));
    }

    @Test
    void nullQueryReturnsEmpty() throws Exception {
        when(searchService.searchDetailed(isNull(), anyInt())).thenReturn(SearchOutcome.empty());

        String json = tool.webSearch(null, null);

        assertNotNull(json);
        assertTrue(json.contains("\"total\":0"));
    }

    @Test
    void blankQueryReturnsEmpty() throws Exception {
        when(searchService.searchDetailed("   ", 10)).thenReturn(SearchOutcome.empty());

        String json = tool.webSearch("   ", null);

        assertNotNull(json);
        assertTrue(json.contains("\"total\":0"));
    }

    @Test
    void resultJsonCanBeParsedByObjectMapper() throws Exception {
        when(searchService.searchDetailed("t", 10)).thenReturn(healthy(List.of(
                new SearchResult("Title", "https://url.com", "Snippet"))));

        String json = tool.webSearch("t", null);

        var parsed = objectMapper.readTree(json);
        assertEquals("t", parsed.get("query").asText());
        assertEquals(1, parsed.get("total").asInt());
        assertTrue(parsed.has("results"));
        assertEquals("Title", parsed.get("results").get(0).get("title").asText());
        assertEquals("Snippet", parsed.get("results").get(0).get("snippet").asText());
    }

    @Test
    void serviceExceptionNeverThrows() {
        doThrow(new RuntimeException("search failed")).when(searchService)
                .searchDetailed(anyString(), anyInt());
        assertDoesNotThrow(() -> tool.webSearch("crash", null));
    }

    @Test
    void nullResultNeverThrows() {
        doReturn(null).when(searchService).searchDetailed(anyString(), anyInt());
        assertDoesNotThrow(() -> tool.webSearch("nullResult", null));
    }
}
