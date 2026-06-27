package uno.zhuchen.workflow.state;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import java.util.HashMap;
import java.util.Map;

/**
 * TripPlanningState 的状态键策略工厂
 *
 * Spring AI Alibaba Graph 用 KeyStrategy 决定每个 state key 在节点返回
 * 新值时如何合并:
 * - ReplaceStrategy — 整值替换（默认行为）
 * - AppendStrategy — 列表追加（用于累积日志/警告）
 *
 * 所有 key 集中在此处定义，新增 key 时只需修改本文件 + TripPlanningStateKeys。
 */
public final class KeyStrategyFactoryProvider {

    private KeyStrategyFactoryProvider() {
    }

    /**
     * 创建 TripPlanningState 的 KeyStrategy 工厂
     *
     * key 与策略对应关系:
     * - raw_request / conversation_id / trace_id → Replace（入口参数，不变）
     * - constraints → Replace（ManagerAgent 重写）
     * - route / itinerary / budget → Replace（Worker 输出整值替换）
     * - validation_report → Replace（每次校验重写）
     * - next_node → Replace（每节点重写路由）
     * - iteration_count → Replace（节点内自增后整值替换）
     * - warnings → Append（累积警告列表）
     * - final_report / status → Replace（终态整值）
     */
    public static KeyStrategyFactory create() {
        return () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();

            // INPUT — 入口参数
            strategies.put(TripPlanningStateKeys.INPUT_RAW_REQUEST, new ReplaceStrategy());
            strategies.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, new ReplaceStrategy());
            strategies.put(TripPlanningStateKeys.INPUT_TRACE_ID, new ReplaceStrategy());

            // CONSTRAINTS — 主管输出
            strategies.put(TripPlanningStateKeys.CONSTRAINTS, new ReplaceStrategy());

            // WORKER — Worker 输出
            strategies.put(TripPlanningStateKeys.WORKER_ROUTE, new ReplaceStrategy());
            strategies.put(TripPlanningStateKeys.WORKER_ITINERARY, new ReplaceStrategy());
            strategies.put(TripPlanningStateKeys.WORKER_BUDGET, new ReplaceStrategy());

            // VALIDATION — 校验输出
            strategies.put(TripPlanningStateKeys.VALIDATION_REPORT, new ReplaceStrategy());

            // CONTROL — 控制流
            strategies.put(TripPlanningStateKeys.CONTROL_NEXT_NODE, new ReplaceStrategy());
            strategies.put(TripPlanningStateKeys.CONTROL_ITERATION_COUNT, new ReplaceStrategy());
            strategies.put(TripPlanningStateKeys.CONTROL_WARNINGS, new AppendStrategy());

            // OUTPUT — 终态
            strategies.put(TripPlanningStateKeys.OUTPUT_FINAL_REPORT, new ReplaceStrategy());
            strategies.put(TripPlanningStateKeys.OUTPUT_STATUS, new ReplaceStrategy());

            return strategies;
        };
    }
}
