package uno.zhuchen.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.zhuchen.agent.agent.ReactAgent;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.llm.impl.DashScopeChatModel;
import uno.zhuchen.agent.memory.ChatMemory;
import uno.zhuchen.agent.memory.InMemoryChatMemory;
import uno.zhuchen.agent.tool.ToolRegistry;

/**
 * Agent 核心配置 — 组装所有 Bean 依赖
 *
 * 职责：
 *   - 初始化 ChatModel（LLM 调用层）
 *   - 初始化 ChatMemory（记忆层）
 *   - 组装 ReactAgent（注入 ChatModel + ChatMemory + AgentConfig）
 */
@Configuration
@EnableConfigurationProperties(AgentConfig.class)
public class LLMConfig {

    /**
     * LLM 调用层 — DashScope 实现
     *
     * 注入 Spring AI 的 ChatModel（由 dashscope-starter 自动配置），
     * DashScopeChatModel 直接调用其 call/stream 方法，绕过 ChatClient 的 advisor 链。
     */
    @Bean
    public ChatModel chatModel(org.springframework.ai.chat.model.ChatModel dashScopeChatModel) {
        return new DashScopeChatModel(dashScopeChatModel);
    }

    /**
     * 对话记忆层 — 内存实现（开发/演示阶段用）
     *
     * 后续可替换为 RedisChatMemory / MysqlChatMemory 等持久化实现。
     */
    @Bean
    public ChatMemory chatMemory() {
        return new InMemoryChatMemory();
    }

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
