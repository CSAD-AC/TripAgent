package uno.zhuchen.agent.mcpserver.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import uno.zhuchen.agent.mcpserver.config.SearchConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 快速诊断：检查 .env 中的 API 密钥是否正确加载到 Spring 环境
 */
@SpringBootTest
@Tag("integration")
class EnvLoadingTest {

    private static final Logger log = LoggerFactory.getLogger(EnvLoadingTest.class);

    @Autowired
    private Environment env;

    @Autowired
    private SearchConfig searchConfig;

    @Test
    void printActualApiKeys() {
        // 从 Environment 直接读取
        String baiduKey = env.getProperty("search.cloud.baidu-api-key");
        String tavilyKey = env.getProperty("search.cloud.tavily-api-key");
        String baiduKey2 = env.getProperty("BAIDU_API_KEY");
        String tavilyKey2 = env.getProperty("TAVILY_API_KEY");

        log.info("===== 环境变量诊断 =====");
        log.info("search.cloud.baidu-api-key = '{}' (len={})", baiduKey, baiduKey != null ? baiduKey.length() : 0);
        log.info("search.cloud.tavily-api-key = '{}' (len={})", tavilyKey, tavilyKey != null ? tavilyKey.length() : 0);
        log.info("BAIDU_API_KEY (env) = '{}' (len={})", baiduKey2, baiduKey2 != null ? baiduKey2.length() : 0);
        log.info("TAVILY_API_KEY (env) = '{}' (len={})", tavilyKey2, tavilyKey2 != null ? tavilyKey2.length() : 0);

        // 从 SearchConfig bean 读取
        log.info("SearchConfig.baiduApiKey = '{}' (len={})", searchConfig.getBaiduApiKey(),
                searchConfig.getBaiduApiKey() != null ? searchConfig.getBaiduApiKey().length() : 0);
        log.info("SearchConfig.tavilyApiKey = '{}' (len={})", searchConfig.getTavilyApiKey(),
                searchConfig.getTavilyApiKey() != null ? searchConfig.getTavilyApiKey().length() : 0);
        log.info("SearchConfig.provider = '{}'", searchConfig.getProvider());
        log.info("===== 诊断结束 =====");
    }
}
