package uno.zhuchen.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
 *   - 初始化 ChatModel（LLM 调用层，支持 DashScope / DeepSeek 切换）
 *   - 初始化 ChatMemory（记忆层）
 *   - 组装 ReactAgent（注入 ChatModel + ChatMemory + AgentConfig）
 *
 * LLM 提供者通过 Spring profile 切换：
 *   - 默认 / dashscope：使用阿里云 DashScope（通义千问）
 *   - deepseek：使用 DeepSeek
 */
@Configuration
@EnableConfigurationProperties(AgentConfig.class)
public class LLMConfig {

    /**
     * LLM 调用层 — DashScope 实现（默认 profile）
     *
     * 注入 Spring AI 的 ChatModel（由 dashscope-starter 自动配置），
     * DashScopeChatModel 直接调用其 call/stream 方法，绕过 ChatClient 的 advisor 链。
     */
    @Bean
    @Profile("dashscope")
    public ChatModel chatModel(org.springframework.ai.chat.model.ChatModel dashScopeChatModel,
                               @Value("${spring.ai.dashscope.chat.options.multi-model:false}") boolean multiModel) {
        return new DashScopeChatModel(dashScopeChatModel, multiModel);
    }

    /**
     * LLM 调用层 — DeepSeek 实现（deepseek profile）
     *
     * 使用 Spring AI OpenAI 客户端指向 DeepSeek API（https://api.deepseek.com），
     * 因为 Spring AI DeepSeekChatModel 在 v1.1.2 中处理 ToolResponseMessage 有 bug，
     * 改用更成熟的 OpenAiChatModel（兼容 DeepSeek API）。
     *
     * ⚠️ 在 deepseek profile 中已排除 DeepSeekChatAutoConfiguration，
     * 避免与 OpenAiChatModel bean 冲突。
     */
    @Bean
    @Profile("deepseek")
    public ChatModel myDeepSeekChatModel(org.springframework.ai.chat.model.ChatModel openAiChatModel) {
        return new DeepSeekChatModel(openAiChatModel);
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
     * 注入 LLM + 记忆 + 配置 + 工具注册表。
     */
    @Bean
    public ReactAgent reactAgent(ChatModel chatModel, ChatMemory chatMemory,
                                  AgentConfig agentConfig, ToolRegistry toolRegistry) {
        return new ReactAgent(chatModel, chatMemory, agentConfig, toolRegistry);
    }
}
