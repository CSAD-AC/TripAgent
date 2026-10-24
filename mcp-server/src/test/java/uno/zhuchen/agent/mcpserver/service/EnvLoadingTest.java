package uno.zhuchen.agent.mcpserver.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.zhuchen.agent.tools.cloud.config.SearchConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置绑定诊断 - 检查 .env 与 application.yml 是否正确绑定到 Environment 与 SearchConfig
 *
 * <p><b>为什么摘掉 Milvus</b>: 本测试只验证配置绑定, 与向量库无关。但 {@code @SpringBootTest}
 * 会拉起全量上下文, Milvus 未启动时 milvusClient 初始化超时, 上下文直接失败, 测试被连坐报错。
 * 这里用 {@code spring.autoconfigure.exclude} 排除 Milvus 自动配置, 并用 mock 补上
 * {@code VectorStore}(IngestionService 依赖它), 让"配置绑定"这件事只依赖 .env 文件本身。
 */
@SpringBootTest(properties = {
        "rag.seed.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration"
})
@Tag("integration")
class EnvLoadingTest {

    private static final Logger log = LoggerFactory.getLogger(EnvLoadingTest.class);

    @Autowired
    private Environment env;

    @Autowired
    private SearchConfig searchConfig;

    /** 替代真实 Milvus 向量库, 只为满足 IngestionService 的构造依赖 */
    @MockitoBean
    private VectorStore vectorStore;

    @Test
    void searchConfigIsBoundFromEnv() {
        String baiduKey = env.getProperty("search.cloud.baidu-api-key");
        String deepseekKey = env.getProperty("search.cloud.deepseek-api-key");

        log.info("===== 环境变量诊断 =====");
        log.info("search.cloud.provider = '{}'", searchConfig.getProvider());
        log.info("search.cloud.baidu-api-key len = {}", baiduKey == null ? 0 : baiduKey.length());
        log.info("search.cloud.deepseek-api-key len = {}", deepseekKey == null ? 0 : deepseekKey.length());
        log.info("search.cloud.deepseek-base-url = '{}'", searchConfig.getDeepseekBaseUrl());
        log.info("search.cloud.http-timeout-seconds = {}", searchConfig.getHttpTimeoutSeconds());
        log.info("search.cloud.channel-timeout-seconds = {}", searchConfig.getChannelTimeoutSeconds());
        log.info("===== 诊断结束 =====");

        assertNotNull(searchConfig.getProvider(), "provider 必须有值");
        assertFalse(searchConfig.getProvider().isBlank(), "provider 不能为空白");
        assertNotNull(searchConfig.getDeepseekBaseUrl(), "DeepSeek Base URL 必须有默认值");

        // 超时预算不变式: 守卫时长必须覆盖 DeepSeek 重试最坏耗时(两次 HTTP + 重试间隔),
        // 否则重试永远来不及完成, 故障会被误记成 timeout 而掩盖真实错误码
        assertTrue(searchConfig.getHttpTimeoutSeconds() > 0, "HTTP 超时必须为正");
        assertTrue(searchConfig.getChannelTimeoutSeconds() >= 2 * searchConfig.getHttpTimeoutSeconds(),
                "通道守卫(" + searchConfig.getChannelTimeoutSeconds() + "s) 必须 >= 2*HTTP 超时("
                        + searchConfig.getHttpTimeoutSeconds() + "s)");
    }
}
