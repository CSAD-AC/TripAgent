package uno.zhuchen.agent.mcpserver.service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * MCP 工具分组开关配置。
 *
 * <p>直接从 {@link Environment} 读取 {@code mcp.tool-groups.*} 配置，
 * 避免 {@code @ConfigurationProperties} 嵌套 Map 绑定的时序问题。
 *
 * <p>不配置时所有工具默认启用，兼容旧版无配置状态。
 */
@Component
public class McpToolProperties {

    private static final Logger log = LoggerFactory.getLogger(McpToolProperties.class);

    private static final String PREFIX = "mcp.tool-groups.";

    private final Environment env;

    public McpToolProperties(Environment env) {
        this.env = env;
        log.info("McpToolProperties 已初始化，配置前缀: {}", PREFIX);
    }

    /**
     * 判断指定分组是否启用。
     *
     * @param groupKey 分组 key，如 amap / ticket12306
     * @return 未配置时默认 {@code true}
     */
    public boolean isGroupEnabled(String groupKey) {
        String key = PREFIX + groupKey + ".enabled";
        String val = env.getProperty(key);
        if (val == null) {
            return true; // 未配置 = 默认启用
        }
        return "true".equalsIgnoreCase(val.trim());
    }

    /**
     * 判断指定分组的指定工具是否启用。
     *
     * @param groupKey 分组 key，如 amap / ticket12306
     * @param toolKey  工具 key，如 weather、ticket-query
     * @return 未配置时默认 {@code true}（但分组禁用时返回 {@code false}）
     */
    public boolean isToolEnabled(String groupKey, String toolKey) {
        if (!isGroupEnabled(groupKey)) {
            return false;
        }
        String key = PREFIX + groupKey + ".tools." + toolKey;
        String val = env.getProperty(key);
        if (val == null) {
            return true; // 未配置 = 默认启用
        }
        return "true".equalsIgnoreCase(val.trim());
    }
}
