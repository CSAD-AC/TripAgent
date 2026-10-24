package uno.zhuchen.agent.tools.cloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 云端搜索配置
 *
 * <p>支持三个搜索后端与多种编排策略, 通过 {@code provider} 切换。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "search.cloud")
public class SearchConfig {

    /**
     * 搜索策略:
     * <ul>
     *   <li>{@code parallel} — 默认。百度 + DeepSeek 并行, RRF 按名次融合;
     *       任一路故障/超时/零结果只损失该路贡献, 不影响另一路</li>
     *   <li>{@code baidu} — 仅百度 Qianfan(排查用)</li>
     *   <li>{@code deepseek} — 仅 DeepSeek Search(排查用)</li>
     *   <li>{@code tavily} — 仅 Tavily(排查用)</li>
     *   <li>{@code baidu-tavily-fallback} — 顺序降级, legacy 策略</li>
     * </ul>
     */
    private String provider = "parallel";

    /** 百度 Qianfan API Key (Authorization: Bearer &lt;key&gt;) */
    private String baiduApiKey;

    /** Tavily API Key (Authorization: Bearer &lt;key&gt;) */
    private String tavilyApiKey;

    /** DeepSeek API Key, 与模型调用共用同一把 Key */
    private String deepseekApiKey;

    /** DeepSeek Base URL, Search/Fetch 接口挂在 {@code /v1/tools/*} 下 */
    private String deepseekBaseUrl = "https://api.deepseek.com";

    /**
     * 单通道 HTTP 超时(秒)
     *
     * <p>单次请求的上限。DeepSeek 通道对 408/429/5xx 会重试一次, 因此最坏耗时约为
     * {@code 2 * httpTimeoutSeconds + 重试间隔}, 必须小于 {@link #channelTimeoutSeconds},
     * 否则重试永远来不及完成, 故障会被误记成 timeout 而掩盖真实错误码。
     */
    private int httpTimeoutSeconds = 12;

    /**
     * 单通道总超时(秒), 并行编排时用于守卫 Future
     *
     * <p>所有通道共用一个 deadline(不是每个 future 各给一份), 所以这里是整次搜索在
     * 等待环节的最坏耗时。缺省 30s = 2 * 12s(HTTP) + 重试间隔, 留有余量。
     */
    private int channelTimeoutSeconds = 30;
}
