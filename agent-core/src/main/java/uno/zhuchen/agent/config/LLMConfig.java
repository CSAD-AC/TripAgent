package uno.zhuchen.agent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.support.RetryTemplate;
import uno.zhuchen.agent.core.agent.ReactAgent;
import uno.zhuchen.agent.core.llm.ChatModel;
import uno.zhuchen.agent.core.llm.impl.DashScopeChatModel;
import uno.zhuchen.agent.core.llm.impl.DeepSeekChatModel;
import uno.zhuchen.agent.core.memory.ChatMemory;
import uno.zhuchen.agent.core.memory.InMemoryChatMemory;
import uno.zhuchen.agent.core.tool.ToolRegistry;

/**
 * Agent 核心配置 — 组装所有 Bean 依赖
 *
 * 职责：
 *   - 初始化 ChatModel（LLM 调用层，DeepSeek 与 DashScope 双实现并存,由 ModelRouter 按 provider 路由）
 *   - 初始化 ChatMemory（记忆层）
 *   - 组装 ReactAgent（注入 ChatModel + ChatMemory + AgentConfig）
 *
 * <p>模型选择改造(2026/08/01): 不再按 profile 二选一注册 ChatModel,
 * 两个实现同时注册, 前端通过 app.models 注册表选择, 未指定时回退注册表第一项.
 * <ul>
 *   <li>DeepSeek: 复用 spring-ai-openai 自动配置的 OpenAiChatModel(deepseek profile 提供 api-key 等配置)</li>
 *   <li>DashScope: 手动构建(走 5 参构造, 自带 DefaultToolExecutionEligibilityPredicate/Validator 兜底),
 *       api-key 从 .env 的 DASHSCOPE_API_KEY 读取, 不依赖 dashscope profile 激活</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AgentConfig.class)
public class LLMConfig {

    /**
     * LLM 调用层 — DeepSeek 实现.
     *
     * 使用 Spring AI OpenAI 客户端指向 DeepSeek API（https://api.deepseek.com），
     * 因为 Spring AI DeepSeekChatModel 在 v1.1.2 中处理 ToolResponseMessage 有 bug，
     * 改用更成熟的 OpenAiChatModel（兼容 DeepSeek API）。
     *
     * 底层 OpenAiChatModel 由 spring-ai-openai 自动配置提供(deepseek profile 下
     * 已配置 api-key/base-url/model); 其他 profile 下 bean 仍存在但 key 为空,
     * 调用时会失败, 由前端模型选择约束.
     *
     * @Primary: 未显式指定模型的消费者(workflow 包 6 个 Agent, 按类型注入 ChatModel)
     * 获得此 bean — 即 Graph 模式用默认模型(与注册表第一项 deepseek 语义一致).
     * ModelRouter 用 @Qualifier 显式选择两个实现, 不受 @Primary 影响.
     */
    @Bean
    @Primary
    public ChatModel deepSeekChatModel(org.springframework.ai.chat.model.ChatModel openAiChatModel) {
        return new DeepSeekChatModel(openAiChatModel);
    }

    /**
     * LLM 调用层 — DashScope 实现(手动构建, 不依赖 starter 自动配置).
     *
     * <p>deepseek profile 下 spring-ai-alibaba 的 DashScope 自动配置被排除,
     * 因此这里手动装配底层 com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel.
     * 走 5 参构造: 其内部会 new DefaultToolExecutionEligibilityPredicate 与
     * DefaultToolCallValidator, 不会因省略这两个参数而 NPE(builder 的 build()
     * 对 null 直接透传会触发 Assert.notNull, 故不用 builder).
     *
     * <p>api-key 直接读 .env 的 DASHSCOPE_API_KEY, 不依赖 dashscope profile 块.
     */
    @Bean
    public ChatModel dashScopeChatModel(ToolCallingManager toolCallingManager,
                                        RetryTemplate retryTemplate,
                                        ObservationRegistry observationRegistry,
                                        @Value("${DASHSCOPE_API_KEY:}") String apiKey,
                                        @Value("${spring.ai.dashscope.chat.options.multi-model:false}") boolean multiModel) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
        DashScopeChatOptions options = new DashScopeChatOptions();
        com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel underlying =
                new com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel(
                        api, options, toolCallingManager, retryTemplate, observationRegistry);
        return new DashScopeChatModel(underlying, multiModel);
    }

    /**
     * 对话记忆层 — 内存实现（dev / test 开发/测试阶段用，无需 MySQL）
     *
     * ⚠️ 方法名不能叫 chatMemory，因为 Spring AI 的
     * ChatMemoryAutoConfiguration 已注册了一个同名 bean。
     */
    @Bean
    @Profile("dev || test")
    public ChatMemory inMemoryChatMemory() {
        return new InMemoryChatMemory();
    }

    /**
     * 对话记忆层 — MyBatis-Plus + Redis 实现（生产环境）
     *
     * !dev && !test 时启用，确保 dev/test 下无需启动 MySQL/Redis。
     * 由 PersistentChatMemory 的 @Component + @Profile 自动注册。
     */

    /**
     * ReAct 循环核心
     *
     * 注入模型路由器(按 modelId 选择 LLM 实现) + 记忆 + 配置 + 工具注册表。
     */
    @Bean
    public ReactAgent reactAgent(ModelRouter modelRouter, ChatMemory chatMemory,
                                  AgentConfig agentConfig, ToolRegistry toolRegistry) {
        return new ReactAgent(modelRouter, chatMemory, agentConfig, toolRegistry);
    }
}
