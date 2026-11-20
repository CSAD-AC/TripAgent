package uno.zhuchen.agent.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.zhuchen.agent.config.ModelRouter;

/**
 * 记忆抽取器装配 — 按开关在"规则"与"规则 + LLM"之间切换。
 *
 * <p>只有一个 {@link MemoryCandidateExtractor} bean, 由这里按配置决定实现,
 * 避免两个实现同时注册导致注入歧义(也避免改动既有构造注入点)。
 */
@Slf4j
@Configuration
public class MemoryExtractionConfig {

    @Bean
    public MemoryCandidateExtractor memoryCandidateExtractor(
            MemoryExtractor extractor,
            ModelRouter modelRouter,
            ObjectMapper objectMapper,
            @Value("${app.memory.extraction.llm-enabled:false}") boolean llmEnabled,
            @Value("${app.memory.extraction.model:}") String modelId,
            @Value("${app.memory.extraction.max-candidates:5}") int maxCandidates,
            @Value("${app.memory.extraction.max-value-chars:40}") int maxValueChars) {
        RuleMemoryCandidateExtractor rule = new RuleMemoryCandidateExtractor(extractor, objectMapper);
        if (!llmEnabled) {
            log.info("[memory] 记忆抽取器: rule-v1 (LLM 深度抽取未启用)");
            return rule;
        }
        log.info("[memory] 记忆抽取器: llm-v1 (modelId={}, 失败回退 rule-v1)", modelId);
        return new LlmMemoryCandidateExtractor(
                modelRouter,
                objectMapper,
                rule,
                modelId,
                maxCandidates,
                maxValueChars);
    }
}
