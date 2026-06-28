package uno.zhuchen.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * OpenAiApi 超时配置 — 覆盖 Spring AI 自动配置的默认超时。
 *
 * <p>Spring AI 自动配置的 OpenAiApi 使用框架内置默认超时（约 10s），
 * 但大模型生成长文本时响应耗时可能远超此值，导致 Netty ReadTimeoutException。
 * 本配置通过自定义 RestClient.Builder 显式设置连接/读取超时，覆盖默认行为。
 *
 * <p>仅在 deepseek profile 下生效（此时 openai API 指向 DeepSeek）。
 */
@Configuration
@Profile("deepseek")
public class OpenAiTimeoutConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTimeoutConfig.class);

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.connect-timeout:30s}")
    private Duration connectTimeout;

    @Value("${spring.ai.openai.read-timeout:120s}")
    private Duration readTimeout;

    /**
     * 自定义 OpenAiApi bean，覆盖自动配置的默认实例。
     *
     * <p>核心：通过 ClientHttpRequestFactorySettings 显式设置 connectTimeout 和 readTimeout，
     * 确保 Netty/HTTP 客户端使用正确的超时时间。
     */
    @Bean
    public OpenAiApi openAiApi() {
        log.info("[OpenAiTimeoutConfig] 初始化 OpenAiApi: baseUrl={}, connectTimeout={}, readTimeout={}",
                baseUrl, connectTimeout, readTimeout);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
        requestFactory.setReadTimeout((int) readTimeout.toMillis());

        RestClient.Builder customRestClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(customRestClientBuilder)
                .build();

        log.info("[OpenAiTimeoutConfig] OpenAiApi 创建成功");
        return api;
    }
}
