package uno.zhuchen.agent.core.multi;

import uno.zhuchen.agent.domain.dto.StreamChunk;

import java.util.function.Consumer;

/**
 * 透明化子代理工具 — 方案 C 的事件总线抽象
 *
 * <p>SubAgent / TripPlanningTool 都实现本接口, 让主管调度器(MultiAgentManager)
 * 以统一方式调用"可透明观察的执行过程", 而不是把子代理当黑盒字符串工具.
 *
 * <p>与 {@link org.springframework.ai.tool.ToolCallback} 的关系:
 * <ul>
 *   <li>ToolCallback 负责"注册为工具"(name/schema/同步 call), 供主管 LLM 决策</li>
 *   <li>本接口负责"可观测的执行通道": 调用方显式传入 conversationId / traceId /
 *       modelId / progress 回调, 子代理内部完整生命周期(thinking_token / tool_call /
 *       tool_result / final)经 progress 回调实时上抛, 由调用方推入 SSE sink</li>
 * </ul>
 *
 * <p>context 传递: conversationId / traceId / modelId 全部以参数显式传入,
 * 不依赖 Reactor Context(子代理跑在 boundedElastic 阻塞线程上, 拿不到 ContextView),
 * 也不引入 ThreadLocal(askUser 除外, 它仍走 AskUserTool 自身的 ThreadLocal 通道).
 */
public interface TransparentAgentTool {

    /**
     * 带会话上下文 + 模型 + 进度回调的调用入口
     *
     * @param toolInput      主管下发的工具入参 JSON(子代理约定解析其中的 task / request 字段)
     * @param conversationId 会话 ID(askUser 反问需要; 可为 null)
     * @param traceId        链路追踪 ID(日志串联; 可为 null)
     * @param modelId        模型业务 ID(透传给子代理内部 LLM; null = 注册表默认)
     * @param progress       进度回调(发射 node_progress 事件到 SSE; 可为 null)
     * @return 子代理最终结论字符串(仍满足 ToolCallback 的"字符串进出"契约)
     */
    String callTransparent(String toolInput, String conversationId, String traceId,
                           String modelId, Consumer<StreamChunk> progress);
}
