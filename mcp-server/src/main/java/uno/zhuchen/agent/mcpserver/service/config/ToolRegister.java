package uno.zhuchen.agent.mcpserver.service.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.zhuchen.agent.tools.amap.tool.*;
import uno.zhuchen.agent.tools.ticket12306.tool.*;

/**
 * MCP Server 统一工具注册
 *
 * <p>同时注册高德地图和 12306 两套 MCP 工具的 @Bean。
 */
@Configuration
public class ToolRegister {

    /** 高德地图工具 */
    @Bean
    public ToolCallbackProvider amapTools(RoutePlanningTool routePlanningTool,
                                          PoiSearchTool poiSearchTool,
                                          WeatherQueryTool weatherQueryTool,
                                          GeocodeTool geocodeTool,
                                          PageFetchTool pagefetchTool,
                                          WebSearchTool webSearchTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(routePlanningTool, poiSearchTool, weatherQueryTool,
                        geocodeTool, pagefetchTool, webSearchTool)
                .build();
    }

    /** 12306 工具 */
    @Bean
    public ToolCallbackProvider ticket12306Tools(CurrentDateTool currentDateTool,
                                                 StationQueryTool stationQueryTool,
                                                 TicketQueryTool ticketQueryTool,
                                                 InterlineTicketQueryTool interlineTicketQueryTool,
                                                 TrainRouteQueryTool trainRouteQueryTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(currentDateTool, stationQueryTool, ticketQueryTool,
                        interlineTicketQueryTool, trainRouteQueryTool)
                .build();
    }
}
