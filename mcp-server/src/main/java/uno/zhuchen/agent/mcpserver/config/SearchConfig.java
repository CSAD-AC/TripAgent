package uno.zhuchen.agent.mcpserver.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 云端搜索 API 配置
 *
 * <p>支持百度 Qianfan 和 Tavily 两个搜索后端。
 * 通过 provider 字段切换：baidu / tavily / baidu-tavily-fallback
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "search.cloud")
public class SearchConfig {

    /**
     * 搜索后端选择：
     * <ul>
     *   <li>{@code baidu} — 仅使用百度 Qianfan</li>
     *   <li>{@code tavily} — 仅使用 Tavily</li>
     *   <li>{@code baidu-tavily-fallback} — 先百度，失败后降级到 Tavily</li>
     * </ul>
     */
    private String provider = "baidu-tavily-fallback";

    /** 百度 Qianfan API Key (Authorization: Bearer &lt;key&gt;) */
    private String baiduApiKey;

    /** Tavily API Key (Authorization: Bearer &lt;key&gt;) */
    private String tavilyApiKey;
}
