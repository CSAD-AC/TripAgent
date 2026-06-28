package uno.zhuchen.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Server 统一启动入口
 *
 * <p>加载高德地图 (uno.zhuchen.agent.tools.amap) 和
 * 12306 (uno.zhuchen.agent.tools.ticket12306) 两套 MCP 工具。
 */
@SpringBootApplication(scanBasePackages = {
        "uno.zhuchen.agent.mcpserver",
        "uno.zhuchen.agent.tools"
})
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
