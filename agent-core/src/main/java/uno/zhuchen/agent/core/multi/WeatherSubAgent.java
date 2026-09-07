package uno.zhuchen.agent.core.multi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.tool.ToolRegistry;

/**
 * 天气子代理 — 查询指定城市当前/未来天气
 *
 * <p>预设 SubAgent 示例: 对外是 Tool(weatherExpert), 对内持有独立 ChatModel
 * 与 amap 天气工具子集, 内部跑轻量 ReAct 循环.
 */
@Component
public class WeatherSubAgent extends SubAgentBase {

    public WeatherSubAgent(@Lazy ModelRouter modelRouter, ToolRegistry toolRegistry,
                           ObjectMapper objectMapper) {
        super("weatherExpert",
                "天气专家子代理 — 查询指定城市当前/未来天气(温度/天气状况/风向风力), 返回格式化天气信息",
                MultiAgentPrompts.WEATHER_SUBAGENT_SYSTEM_PROMPT,
                modelRouter, toolRegistry, objectMapper, 4);
    }

    @Override
    protected String[] ownToolNames() {
        return new String[]{"amapWeather", "amapGeocode"};
    }
}
