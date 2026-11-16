package uno.zhuchen.agent.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import uno.zhuchen.agent.core.tool.mock.MockWeatherToolCallback;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ToolRegistryTest {

    @Test
    void getAllExceptExcludesNamedTools() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry(
                ToolCallbackProvider.from(new MockWeatherToolCallback()),
                new AskUserToolCallback(mock(AskUserTool.class), objectMapper),
                new CalculatorTool(objectMapper)
        );
        registry.init();

        Set<String> names = Arrays.stream(registry.getAllExcept("askUser"))
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertEquals(Set.of("getWeather", "calculator"), names);
    }
}
