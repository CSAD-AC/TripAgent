package uno.zhuchen.agent.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.SaveMode;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisIndexedWebSession;

@Configuration
@Profile("deepseek & !dev & !test")
@EnableRedisIndexedWebSession(
        maxInactiveIntervalInSeconds = 1800,
        redisNamespace = "travelpal:session",
        saveMode = SaveMode.ON_SET_ATTRIBUTE)
public class ProductionRedisSessionConfig {
}
