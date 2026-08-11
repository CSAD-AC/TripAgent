package uno.zhuchen.agent.core.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 调用抽象 — 隔离具体模型实现
 *
 * 类比 MVC 中的 Mapper 层：定义数据访问契约（此处为 LLM 调用），
 * 具体实现在 impl/ 子包中切换。
 *
 * <p>modelName 参数: API 模型名(如 deepseek-v4-flash / qwen3.6-flash),
 * 由上层(Controller 解析 modelId 后)传入; 传 null 时实现使用各自配置的默认模型.
 * 保留两参默认方法, 供未参与模型选择的调用方(Graph 模式 BaseAgent)使用.
 */
public interface ChatModel {

    /**
     * 同步调用 LLM，返回模型回复
     *
     * @param messages  包含 system prompt 和消息历史的完整列表
     * @param modelName API 模型名; null 表示使用默认模型
     * @return 模型回复（可能含 toolCalls）
     */
    AssistantMessage call(List<Message> messages, String modelName, ToolCallback... tools);

    /**
     * 流式调用 LLM，逐 token 推送
     *
     * @param messages  包含 system prompt 和消息历史的完整列表
     * @param modelName API 模型名; null 表示使用默认模型
     * @return token 流，每个元素为一段文本（可能为空字符串，调用方需过滤）
     */
    Flux<ChatResponse> stream(List<Message> messages, String modelName, ToolCallback... tools);

    /** 便捷入口: 不指定模型, 用默认 */
    default AssistantMessage call(List<Message> messages, ToolCallback... tools) {
        return call(messages, null, tools);
    }

    /** 便捷入口: 不指定模型, 用默认 */
    default Flux<ChatResponse> stream(List<Message> messages, ToolCallback... tools) {
        return stream(messages, null, tools);
    }
}
