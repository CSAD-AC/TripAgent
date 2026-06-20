package uno.zhuchen.agent.mcpserver.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.mcpserver.service.AmapService;

/**
 * 路线规划 MCP 工具
 *
 * <p>提供驾车/步行/骑行/公交路线规划，内部调用高德 v5 路线规划 API。
 */
@Component
public class RoutePlanningTool {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanningTool.class);

    private final AmapService amapService;

    public RoutePlanningTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Tool(description = "驾车路线规划 — 根据起点和终点坐标查询驾车路线，支持多种偏好策略")
    public String amapDrivingRoute(
            @ToolParam(required = true, description = "起点经纬度，格式：经度,纬度，例如 116.434307,39.90909") String origin,
            @ToolParam(required = true, description = "终点经纬度，格式：经度,纬度，例如 116.434446,39.90816") String destination,
            @ToolParam(required = false, description = "算路偏好，可选值：推荐(默认)、高速优先、不走高速、避开拥堵、速度最快、费用优先") String strategy) {
        log.info("驾车路线规划: origin={}, destination={}, strategy={}", origin, destination, strategy);
        return amapService.drivingRoute(origin, destination, strategy);
    }

    @Tool(description = "步行路线规划 — 根据起点和终点坐标查询步行路线方案")
    public String amapWalkingRoute(
            @ToolParam(required = true, description = "起点经纬度，格式：经度,纬度") String origin,
            @ToolParam(required = true, description = "终点经纬度，格式：经度,纬度") String destination) {
        log.info("步行路线规划: origin={}, destination={}", origin, destination);
        return amapService.walkingRoute(origin, destination);
    }

    @Tool(description = "骑行路线规划 — 根据起点和终点坐标查询骑行路线方案")
    public String amapBicyclingRoute(
            @ToolParam(required = true, description = "起点经纬度，格式：经度,纬度") String origin,
            @ToolParam(required = true, description = "终点经纬度，格式：经度,纬度") String destination) {
        log.info("骑行路线规划: origin={}, destination={}", origin, destination);
        return amapService.bicyclingRoute(origin, destination);
    }

    @Tool(description = "公交/地铁路线规划 — 查询两个地点之间的公交、地铁换乘方案")
    public String amapTransitRoute(
            @ToolParam(required = true, description = "起点经纬度，格式：经度,纬度") String origin,
            @ToolParam(required = true, description = "终点经纬度，格式：经度,纬度") String destination,
            @ToolParam(required = true, description = "所在城市，支持城市中文名（如 北京、上海）或 citycode（如 010、021）或 adcode（如 110000、310000）") String city) {
        log.info("公交路线规划: origin={}, destination={}, city={}", origin, destination, city);
        return amapService.transitRoute(origin, destination, city);
    }
}
