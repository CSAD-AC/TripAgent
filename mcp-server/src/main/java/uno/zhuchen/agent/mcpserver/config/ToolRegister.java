package uno.zhuchen.agent.mcpserver.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.zhuchen.agent.mcpserver.tool.GeocodeTool;
import uno.zhuchen.agent.mcpserver.tool.PoiSearchTool;
import uno.zhuchen.agent.mcpserver.tool.RoutePlanningTool;
import uno.zhuchen.agent.mcpserver.tool.WeatherQueryTool;

/**
 * MCP 工具注册配置
 *
 * <p>将 @Tool 注解的方法注册为 MCP 协议可调用的工具。
 * spring-ai-starter-mcp-server-webflux 自动配置会扫描 ToolCallbackProvider Bean，
 * 将工具注册到 McpAsyncServer 中，通过 SSE 暴露给 MCP 客户端。
 */
@Configuration
public class ToolRegister {

    @Bean
    public ToolCallbackProvider amapTools(RoutePlanningTool routePlanningTool,
                                          PoiSearchTool poiSearchTool,
                                          WeatherQueryTool weatherQueryTool,
                                          GeocodeTool geocodeTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(routePlanningTool, poiSearchTool, weatherQueryTool, geocodeTool)
                .build();
    }
}
