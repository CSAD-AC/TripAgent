package uno.zhuchen.agent.mcpserver.service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.zhuchen.agent.tools.amap.tool.*;
import uno.zhuchen.agent.tools.ticket12306.tool.*;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;

/**
 * MCP 工具统一注册器。
 *
 * <p>通过 Spring 参数注入获取所有工具 Bean（确保创建顺序），
 * 再根据 {@link McpToolProperties} 配置决定哪些工具在本次启动注册。
 *
 * <p>新增工具需要在此添加对应的构造参数和 {@code register()} 行，
 * 工具的独立开关由 {@code application.yml} 的 {@code mcp.tool-groups.*.tools} 控制。
 */
@Configuration
public class ToolRegister {

    private static final Logger log = LoggerFactory.getLogger(ToolRegister.class);

    /** 高德地图工具组 */
    @Bean
    public ToolCallbackProvider amapTools(RoutePlanningTool routePlanningTool,
                                          PoiSearchTool poiSearchTool,
                                          WeatherQueryTool weatherQueryTool,
                                          GeocodeTool geocodeTool,
                                          PageFetchTool pagefetchTool,
                                          WebSearchTool webSearchTool,
                                          McpToolProperties props) {
        return buildProvider("amap", props,
                register("geocode", geocodeTool),
                register("route-planning", routePlanningTool),
                register("poi-search", poiSearchTool),
                register("weather", weatherQueryTool),
                register("page-fetch", pagefetchTool),
                register("web-search", webSearchTool));
    }

    /** 12306 工具组 */
    @Bean
    public ToolCallbackProvider ticket12306Tools(CurrentDateTool currentDateTool,
                                                 StationQueryTool stationQueryTool,
                                                 TicketQueryTool ticketQueryTool,
                                                 InterlineTicketQueryTool interlineTicketQueryTool,
                                                 TrainRouteQueryTool trainRouteQueryTool,
                                                 McpToolProperties props) {
        return buildProvider("ticket12306", props,
                register("current-date", currentDateTool),
                register("station", stationQueryTool),
                register("ticket-query", ticketQueryTool),
                register("interline-ticket", interlineTicketQueryTool),
                register("train-route", trainRouteQueryTool));
    }

    // ========== 辅助方法 ==========

    /**
     * 创建工具注册条目（key + 工具实例）。
     *
     * <p>用法: {@code register("weather", weatherQueryTool)}
     *
     * @param toolKey YAML 中的工具 key，如 {@code weather}
     * @param tool    工具 Bean 实例
     * @return Map.Entry
     */
    private static Map.Entry<String, Object> register(String toolKey, Object tool) {
        return new AbstractMap.SimpleImmutableEntry<>(toolKey, tool);
    }

    /**
     * 按分组配置过滤并构建 {@link ToolCallbackProvider}。
     *
     * @param groupKey 分组 key，如 amap / ticket12306
     * @param props    全局配置
     * @param entries  该分组下的所有工具注册条目
     * @return 仅包含已启用工具的 Provider
     */
    private static ToolCallbackProvider buildProvider(String groupKey,
                                                      McpToolProperties props,
                                                      Map.Entry<String, Object>... entries) {
        // 分组级禁用检查
        if (!props.isGroupEnabled(groupKey)) {
            log.info("MCP 工具分组 [{}] 已禁用，跳过注册", groupKey);
            return MethodToolCallbackProvider.builder().toolObjects().build();
        }

        Object[] enabled = Arrays.stream(entries)
                .filter(e -> {
                    boolean ok = props.isToolEnabled(groupKey, e.getKey());
                    if (!ok) {
                        log.info("MCP 工具 [{}/{}] 已禁用，跳过注册", groupKey, e.getKey());
                    }
                    return ok;
                })
                .map(Map.Entry::getValue)
                .toArray();

        log.info("MCP 工具分组 [{}] 注册 {} 个工具", groupKey, enabled.length);
        return MethodToolCallbackProvider.builder()
                .toolObjects(enabled)
                .build();
    }
}
