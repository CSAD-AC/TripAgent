package uno.zhuchen.agent.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 运行时配置
 */
@Data
@ConfigurationProperties(prefix = "agent.react")
public class AgentConfig {

    /** 最大 ReAct 迭代次数 */
    private int maxIterations = 20;

    /** 默认 system prompt */
    private String systemPrompt = """
            你是一个专业、友好的智能助手。
            请仔细分析用户的问题，给出准确、清晰的回答。
            如果问题需要多步推理，请先展示你的思考过程，再给出最终答案。
            """;

    /** 超时时间（秒） */
    private int timeoutSeconds = 30;
}
