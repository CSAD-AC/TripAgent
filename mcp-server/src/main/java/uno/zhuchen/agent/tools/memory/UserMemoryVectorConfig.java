package uno.zhuchen.agent.tools.memory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 开启 {@link UserMemoryVectorProperties} 的属性绑定。 */
@Configuration
@EnableConfigurationProperties(UserMemoryVectorProperties.class)
public class UserMemoryVectorConfig {
}
