package uno.zhuchen.agent.tool.mock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

@Slf4j
public class MockWeatherToolCallback extends BaseMockToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("getWeather")
                .description("获取指定城市的天气信息（Mock实现）")
                .inputSchema("""
                {
                    "type": "object",
                    "properties": {
                        "city": {
                            "type": "string",
                            "description": "城市名称，如：深圳、北京、上海"
                        },
                        "date": {
                            "type": "string",
                            "description": "日期，格式：YYYY-MM-DD"
                        }
                    },
                    "required": ["city"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        log.info("Mock天气工具被调用，输入参数: {}", toolInput);

        // 解析参数
        String city = "深圳";
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(toolInput);
            if (json.has("city")) {
                city = json.get("city").asText();
            }
        } catch (Exception e) {
            log.warn("解析参数失败，使用默认城市: {}", e.getMessage());
        }

        // 模拟天气数据
        String mockResponse = String.format("""
            {
                "success": true,
                "data": {
                    "city": "%s",
                    "temperature": %d,
                    "weather": "%s",
                    "humidity": %d,
                    "windSpeed": %d,
                    "updateTime": "%s",
                    "message": "这是Mock数据，用于开发测试"
                }
            }
            """,
                city,
                22 + (int)(Math.random() * 15), // 随机温度
                new String[]{"晴", "多云", "小雨", "阴", "阵雨"}[ (int)(Math.random() * 5) ],
                50 + (int)(Math.random() * 40), // 随机湿度
                5 + (int)(Math.random() * 20), // 随机风速
                java.time.LocalDateTime.now().toString()
        );

        return mockResponse;
    }
}