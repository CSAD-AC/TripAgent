package uno.zhuchen.agent.tool.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

@Slf4j
public abstract class BaseMockToolCallback implements ToolCallback {

    protected String getDefaultInputSchema() {
        return """
            {
                "type": "object",
                "properties": {},
                "required": []
            }
            """;
    }

    protected String buildResponse(Map<String, Object> data) throws JsonProcessingException {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(data);
    }
}