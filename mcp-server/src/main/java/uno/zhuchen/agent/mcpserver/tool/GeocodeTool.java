package uno.zhuchen.agent.mcpserver.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.mcpserver.service.AmapService;

/**
 * 地理编码 MCP 工具
 *
 * <p>提供地址→坐标（地理编码）和坐标→地址（逆地理编码）转换，
 * 配合路线规划工具使用：先用地理编码查坐标，再查路线。
 */
@Component
public class GeocodeTool {

    private static final Logger log = LoggerFactory.getLogger(GeocodeTool.class);

    private final AmapService amapService;

    public GeocodeTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Tool(description = "地理编码 — 将地名或地址转换为经纬度坐标，例如「北京」→ 116.397428,39.90923。配合路线规划工具使用")
    public String amapGeocode(
            @ToolParam(required = true, description = "地址描述，如 北京市、广东省广州市、天安门广场") String address,
            @ToolParam(required = false, description = "所在城市（可选，填写后可提高解析精确度），例如 北京 或 010") String city) {
        log.info("地理编码: address={}, city={}", address, city);
        return amapService.geocode(address, city);
    }

    @Tool(description = "逆地理编码 — 将经纬度坐标转换为详细地址信息，例如 116.397428,39.90923 → 北京市东城区东华门街道天安门广场")
    public String amapReverseGeocode(
            @ToolParam(required = true, description = "经纬度坐标，格式：经度,纬度，例如 116.397428,39.90923") String location) {
        log.info("逆地理编码: location={}", location);
        return amapService.reverseGeocode(location);
    }
}
