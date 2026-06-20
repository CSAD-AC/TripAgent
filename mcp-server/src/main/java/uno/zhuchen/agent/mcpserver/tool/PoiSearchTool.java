package uno.zhuchen.agent.mcpserver.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.mcpserver.service.AmapService;

/**
 * POI 搜索 MCP 工具
 *
 * <p>提供关键词搜索和周边搜索，内部调用高德 v5 POI 搜索 2.0 API。
 */
@Component
public class PoiSearchTool {

    private static final Logger log = LoggerFactory.getLogger(PoiSearchTool.class);

    private final AmapService amapService;

    public PoiSearchTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Tool(description = "关键词搜索兴趣点 — 根据关键词和城市搜索景点、餐厅、酒店、加油站等场所")
    public String amapPoiSearch(
            @ToolParam(required = true, description = "查询关键词，例如 天安门、故宫、北京烤鸭、加油站") String keywords,
            @ToolParam(required = false, description = "城市范围（城市中文名/citycode/adcode），例如 北京 或 010。不传则全国范围搜索") String city) {
        log.info("POI搜索: keywords={}, city={}", keywords, city);
        return amapService.poiTextSearch(keywords, city);
    }

    @Tool(description = "周边搜索 — 根据中心点坐标搜索附近一定范围内的兴趣点（酒店、餐厅、加油站等）")
    public String amapPoiAround(
            @ToolParam(required = true, description = "中心点经纬度，格式：经度,纬度，例如 116.397428,39.90923") String location,
            @ToolParam(required = false, description = "查询关键词，例如 酒店、餐厅、加油站。不传则返回附近所有POI") String keywords,
            @ToolParam(required = false, description = "搜索半径，单位米，默认 1000 米，最大 50000 米") Integer radius) {
        log.info("POI周边搜索: location={}, keywords={}, radius={}", location, keywords, radius);
        return amapService.poiAroundSearch(location, keywords, radius);
    }
}
