package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.workflow.state.BudgetPlan;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.DayPlan;
import uno.zhuchen.workflow.state.RouteResult;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.state.ValidationReport;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 抽象基类
 *
 * 所有 Phase 3 Agent 都继承本类，统一实现以下职责:
 * - 实现 NodeAction 接口（Graph 框架硬性要求）
 * - 提供类型安全的 state 读写辅助方法（消除重复强转）
 * - 模板方法 apply() 统一处理日志 + 异常，子类只关心 execute()
 * - 持有 ChatModel 引用（复用 uno.zhuchen.agent.llm.ChatModel，复用 Phase 1 沉淀）
 *
 * 为什么不新建 LLMService 接口:
 * - uno.zhuchen.agent.llm.ChatModel 已是成熟抽象（Phase 1 验证过）
 * - workflow 单向依赖 agent，直接复用避免重复抽象
 * - callLLM(system, user) helper 已足够满足 Agent 调用需求
 *
 * 子类只需实现 {@link #doExecute(OverAllState)},返回 state 增量。
 */
public abstract class BaseAgent implements NodeAction {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** LLM 调用抽象（直接复用 agent 包的 ChatModel） */
    protected final ChatModel chatModel;

    /** Agent 名称（用于日志和 Graph 节点 ID） */
    protected final String agentName;

    protected BaseAgent(String agentName, ChatModel chatModel) {
        this.agentName = agentName;
        this.chatModel = chatModel;
    }

    /**
     * 模板方法 — Graph 框架调用入口
     *
     * 子类不应重写本方法，应重写 doExecute()。
     */
    @Override
    public final Map<String, Object> apply(OverAllState state) throws Exception {
        long start = System.currentTimeMillis();
        log.debug("[{}] apply() 入口, conversationId={}, iterationCount={}",
                agentName,
                state.value(TripPlanningStateKeys.INPUT_CONVERSATION_ID).orElse("unknown"),
                state.value(TripPlanningStateKeys.CONTROL_ITERATION_COUNT).orElse(0));

        try {
            Map<String, Object> result = doExecute(state);
            long duration = System.currentTimeMillis() - start;
            log.debug("[{}] doExecute 完成, 耗时 {}ms, 输出 keys={}",
                    agentName, duration, result.keySet());
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[{}] doExecute 失败, 耗时 {}ms, error={}",
                    agentName, duration, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 业务执行入口 — 子类实现
     *
     * @param state 共享状态
     * @return state 增量（被 KeyStrategy 合并）
     */
    protected abstract Map<String, Object> doExecute(OverAllState state) throws Exception;

    /**
     * 便捷 LLM 调用 — 适配器方法
     *
     * 把 (systemPrompt, userPrompt) 包装成 Message 列表调用 ChatModel。
     * 多数 Agent 只需要文本回复，不需要工具回调，所以不暴露 tools 参数。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 文本回复
     */
    protected String callLLM(String systemPrompt, String userPrompt) {
        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        );
        // 显式传空 vararg,便于 mockito 严格模式 stub
        return chatModel.call(messages, new org.springframework.ai.tool.ToolCallback[0]).getText();
    }

    // ============ 类型安全的 state 读取辅助方法 ============

    /** 读取 Constraints（必填） */
    protected Constraints getConstraints(OverAllState state) {
        return getTyped(state, TripPlanningStateKeys.CONSTRAINTS, Constraints.class);
    }

    /** 读取 Constraints（可选） */
    protected Optional<Constraints> findConstraints(OverAllState state) {
        return findTyped(state, TripPlanningStateKeys.CONSTRAINTS, Constraints.class);
    }

    /** 读取 RouteResult（可选） */
    protected Optional<RouteResult> findRoute(OverAllState state) {
        return findTyped(state, TripPlanningStateKeys.WORKER_ROUTE, RouteResult.class);
    }

    /** 读取 List<DayPlan>（可选） */
    @SuppressWarnings("unchecked")
    protected Optional<List<DayPlan>> findItinerary(OverAllState state) {
        return state.value(TripPlanningStateKeys.WORKER_ITINERARY)
                .map(v -> (List<DayPlan>) v);
    }

    /** 读取 BudgetPlan（可选） */
    protected Optional<BudgetPlan> findBudget(OverAllState state) {
        return findTyped(state, TripPlanningStateKeys.WORKER_BUDGET, BudgetPlan.class);
    }

    /** 读取 ValidationReport（可选） */
    protected Optional<ValidationReport> findValidationReport(OverAllState state) {
        return findTyped(state, TripPlanningStateKeys.VALIDATION_REPORT, ValidationReport.class);
    }

    /**
     * 通用类型安全读取（必填）
     */
    protected <T> T getTyped(OverAllState state, String key, Class<T> type) {
        return findTyped(state, key, type).orElseThrow(() ->
                new IllegalStateException(String.format(
                        "[%s] state 缺少 key=%s, 类型=%s", agentName, key, type.getSimpleName())));
    }

    /**
     * 通用类型安全读取（可选）
     */
    @SuppressWarnings("unchecked")
    protected <T> Optional<T> findTyped(OverAllState state, String key, Class<T> type) {
        return state.value(key)
                .map(v -> {
                    if (v == null) {
                        return null;
                    }
                    if (!type.isInstance(v)) {
                        throw new IllegalStateException(String.format(
                                "[%s] state key=%s 类型不匹配: 期望 %s, 实际 %s",
                                agentName, key, type.getSimpleName(), v.getClass().getSimpleName()));
                    }
                    return (T) v;
                });
    }
}
