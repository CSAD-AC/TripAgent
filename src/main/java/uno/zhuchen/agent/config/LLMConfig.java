package uno.zhuchen.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.zhuchen.agent.agent.AgentConfig;
import uno.zhuchen.agent.agent.ReactAgent;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.llm.impl.DashScopeChatModel;
import uno.zhuchen.agent.memory.ChatMemory;
import uno.zhuchen.agent.memory.InMemoryChatMemory;

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

    private final ChatClient.Builder chatClientBuilder;

    public LLMConfig(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    /**
     * LLM 调用层 — DashScope 实现
     */
    @Bean
    public ChatModel chatModel() {
        ChatClient chatClient = chatClientBuilder.build();
        return new DashScopeChatModel(chatClient);
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
     * 注入 LLM + 记忆 + 配置，三者即可独立演进。
     */
    @Bean
    public ReactAgent reactAgent(ChatModel chatModel, ChatMemory chatMemory, AgentConfig agentConfig) {
        return new ReactAgent(chatModel, chatMemory, agentConfig);
    }
}
