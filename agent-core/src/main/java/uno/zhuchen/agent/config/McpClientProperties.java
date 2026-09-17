package uno.zhuchen.agent.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MCP 客户端连接配置 — 收敛散落的 {@code ${mcp.server-url}} 注入点.
 *
 * <p>绑定前缀 {@code mcp}(属性键 {@code mcp.server-url}), 默认指向本地 8081.
 * <p>校验(第 2 层): 标注 {@link Validated} + {@link NotBlank}, 配置值为空串时启动即快速失败,
 * 避免运行时才发现连错/连空 mcp-server.
 */
@Validated
@ConfigurationProperties(prefix = "mcp")
public class McpClientProperties {

    /** mcp-server 基础地址(agent-core 通过 WebClient 调用知识库等 REST) */
    @NotBlank(message = "mcp.server-url 不能为空")
    private String serverUrl = "http://localhost:8081";

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }
}