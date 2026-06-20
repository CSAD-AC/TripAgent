package uno.zhuchen.agent.mcpserver.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.mcpserver.service.AmapService;

/**
 * 天气查询 MCP 工具
 *
 * <p>查询指定城市的实时天气，内部调用高德 v3 天气查询 API。
 */
@Component
public class WeatherQueryTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherQueryTool.class);

    private final AmapService amapService;

    public WeatherQueryTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Tool(description = "实时天气查询 — 查询指定城市当前天气（温度、天气状况、风向风力等）")
    public String amapWeather(
            @ToolParam(required = true, description = "城市 adcode 编码，例如 110000 代表北京、310000 代表上海、440100 代表广州、440300 代表深圳。注意不是城市名称，是 adcode 数字编码") String city) {
        log.info("天气查询: city={}", city);
        return amapService.weatherQuery(city);
    }
}
