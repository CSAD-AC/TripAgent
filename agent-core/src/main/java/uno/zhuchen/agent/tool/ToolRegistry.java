package uno.zhuchen.agent.tool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tool.mock.MockWeatherToolCallback;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ToolRegistry {
    private final Map<String, ToolCallback> toolMap = new ConcurrentHashMap<>();
    private ToolCallbackProvider toolCallbackProvider;

    ToolRegistry(ToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    @PostConstruct
    public void init() {
        // 1. 尝试从 MCP 获取真实工具
        try {
            ToolCallback[] mcpTools = toolCallbackProvider.getToolCallbacks();
            if (mcpTools != null && mcpTools.length > 0) {
                for (ToolCallback tool : mcpTools) {
                    String toolName = tool.getToolDefinition().name();
                    toolMap.put(toolName, tool);
                    log.info("注册 MCP 工具: {}", toolName);
                }
                log.info("成功加载 {} 个 MCP 工具", mcpTools.length);
            } else {
                log.warn("MCP 返回空工具列表，使用 Mock 工具");
                loadMockTools();
            }
        } catch (Exception e) {
            log.warn("MCP 工具加载失败: {}", e.getMessage(), e);
            // 2. 加载 Mock 工具
            loadMockTools();
        }
    }

    private void loadMockTools() {
        log.info("加载 Mock 工具作为兜底方案");
         toolMap.put("mockWeatherTool", new MockWeatherToolCallback());
    }

    public ToolCallback[] getAll() {
        return toolMap.values().toArray(new ToolCallback[0]);
    }

    public ToolCallback getByName(String name) {
        return toolMap.get(name);
    }

    public boolean containsTool(String name) {
        return toolMap.containsKey(name);
    }

    public int getToolCount() {
        return toolMap.size();
    }
}
