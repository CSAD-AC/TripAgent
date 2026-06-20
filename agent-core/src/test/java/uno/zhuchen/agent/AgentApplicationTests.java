package uno.zhuchen.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.ai.dashscope.api-key=sk-test-placeholder"
})
class AgentApplicationTests {

    @Test
    void contextLoads() {
    }
}
