package uno.zhuchen.agent.mcpserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 高德地图 API 配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "amap.api")
public class AmapConfig {

    /** 高德 Web服务 API Key */
    private String key;
}
