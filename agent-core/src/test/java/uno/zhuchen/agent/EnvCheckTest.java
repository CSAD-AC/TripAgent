package uno.zhuchen.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 agent-core 的 .env 文件存在且包含必需的密钥
 *
 * .env 位于 src/main/resources/.env，通过 spring.config.import 加载。
 * 本测试直接读取文件验证内容，不依赖 Spring 上下文。
 *
 * 标记为 integration: 该测试校验的是"本机环境配置", 而 .env 被 .gitignore 忽略,
 * CI runner 上不存在该文件。若不标记, `mvn verify` 在 GitHub Actions 上必然失败。
 * 主 CI 用 -DexcludedGroups=integration 排除它, 需要真实密钥的 integration-tests.yml
 * 会先从 GitHub Secrets 落盘 .env 再运行, 因此该测试仍能在 CI 被覆盖。
 */
@Tag("integration")
class EnvCheckTest {

    private static final Logger log = LoggerFactory.getLogger(EnvCheckTest.class);

    @Test
    void verifyDotenvFileExists() {
        // .env 在 classpath 上: src/main/resources/.env
        Path dotenvPath = Paths.get("src/main/resources/.env").toAbsolutePath().normalize();

        log.info("===== Agent Core .env 诊断 =====");
        log.info("查找路径: {}", dotenvPath);

        assertTrue(Files.exists(dotenvPath), ".env 文件应存在于: " + dotenvPath);

        try {
            Properties props = new Properties();
            props.load(Files.newBufferedReader(dotenvPath));

            String dashscopeKey = props.getProperty("DASHSCOPE_API_KEY");
            log.info("DASHSCOPE_API_KEY = '{}' (len={})",
                    dashscopeKey != null ? dashscopeKey.substring(0, Math.min(8, dashscopeKey.length())) + "..." : "null",
                    dashscopeKey != null ? dashscopeKey.length() : 0);

            assertNotNull(dashscopeKey, "DASHSCOPE_API_KEY 应存在于 .env");
            assertFalse(dashscopeKey.isBlank(), "DASHSCOPE_API_KEY 不应为空");

            log.info("===== 诊断通过 ✅ ===== ");
        } catch (IOException e) {
            fail("读取 .env 失败: " + e.getMessage());
        }
    }
}
