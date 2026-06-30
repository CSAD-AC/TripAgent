package uno.zhuchen.workflow.builder;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.stereotype.Component;
import uno.zhuchen.workflow.agent.BudgetAgent;
import uno.zhuchen.workflow.agent.ItineraryAgent;
import uno.zhuchen.workflow.agent.ManagerAgent;
import uno.zhuchen.workflow.agent.ReportAgent;
import uno.zhuchen.workflow.agent.RouteAgent;
import uno.zhuchen.workflow.agent.ValidationAgent;
import uno.zhuchen.workflow.state.KeyStrategyFactoryProvider;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 旅游规划工作流 Graph 装配器
 *
 * 节点编排:
 * <pre>
 *   START → manager → {first 模式: 提取约束 + 复述确认}
 *                      ├─ user_confirmed → parallel_group (虚拟 fan-out 中转)
 *                      ├─ user_rejected → manager (重提取)
 *                      └─ 部分字段缺失 → 反问 → manager (重试)
 *
 *   校验失败 → validation → manager (回退)
 *             ├─ iteration < 2: LLM 决策 retry/give_up
 *             └─ iteration >= 2: 强制 give_up → report
 *
 *   parallel_group 触发并行:
 *     ┌─→ route     ─┐
 *     └─→ itinerary ─┴─→ budget → validation
 *     (两个 Worker 互不依赖,Budget 必须等两者都完成才启动)
 *
 *   validation → {passed: report, failed: manager}
 *   report → END
 * </pre>
 *
 * 路由表（manager 节点出口）:
 * - worker_group → parallel_group（虚拟 fan-out,触发 Route + Itinerary 并行）
 * - route / itinerary / budget → 对应节点（部分重跑,保留单线串行）
 * - report → report（强制通过）
 * - first / 其他 → manager（重提取）
 *
 * 并行实现机制:
 * Spring AI Alibaba Graph 支持「同起点多条 addEdge 即并行触发」+「多源同汇入即 join 等待」。
 * parallel_group 本身是无操作虚拟节点,仅作 fan-out 入口。
 * manager 出口是 conditional edge(支持 first/report/retry 等分支),无法一条 condition 对应多目标,
 * 所以需要中转节点让 fan-out 入口独立于 manager 的条件路由。
 */
@Component
public class TripPlanningGraphBuilder {

    private final ManagerAgent managerAgent;
    private final RouteAgent routeAgent;
    private final ItineraryAgent itineraryAgent;
    private final BudgetAgent budgetAgent;
    private final ValidationAgent validationAgent;
    private final ReportAgent reportAgent;

    public TripPlanningGraphBuilder(ManagerAgent managerAgent,
                                     RouteAgent routeAgent,
                                     ItineraryAgent itineraryAgent,
                                     BudgetAgent budgetAgent,
                                     ValidationAgent validationAgent,
                                     ReportAgent reportAgent) {
        this.managerAgent = managerAgent;
        this.routeAgent = routeAgent;
        this.itineraryAgent = itineraryAgent;
        this.budgetAgent = budgetAgent;
        this.validationAgent = validationAgent;
        this.reportAgent = reportAgent;
    }

    /**
     * 构建完整 SWV Graph(并行版)
     *
     * 拓扑:manager → parallel_group → {route, itinerary} → budget → validation → report
     */
    public CompiledGraph build() throws GraphStateException {
        StateGraph graph = new StateGraph(KeyStrategyFactoryProvider.create())
                .addNode("manager", node_async(managerAgent))
                .addNode("parallel_group", node_async(state -> Map.of()))
                .addNode("route", node_async(routeAgent))
                .addNode("itinerary", node_async(itineraryAgent))
                .addNode("budget", node_async(budgetAgent))
                .addNode("validation", node_async(validationAgent))
                .addNode("report", node_async(reportAgent));

        // 入口
        graph.addEdge(START, "manager");

        // Manager 出口路由表
        // 读取 next_node,根据语义路由:
        // - "worker_group" → parallel_group（虚拟 fan-out,触发 Route + Itinerary 并行）
        // - "route" / "itinerary" / "budget" → 对应节点（部分重跑,单线串行）
        // - "report" → report（强制通过）
        // - "first" / 默认 → manager（重提取）
        graph.addConditionalEdges("manager",
                edge_async(state -> {
                    String next = state.value(TripPlanningStateKeys.CONTROL_NEXT_NODE)
                            .map(Object::toString)
                            .orElse("first");
                    return next;
                }),
                Map.of(
                        "worker_group", "parallel_group",
                        "route", "route",
                        "itinerary", "itinerary",
                        "budget", "budget",
                        "report", "report",
                        "first", "manager"
                ));

        // parallel_group 触发并行 fan-out:Route 和 Itinerary 同时启动
        // 框架语义:同起点多条 addEdge 自动并行触发下游节点
        graph.addEdge("parallel_group", "route");
        graph.addEdge("parallel_group", "itinerary");

        // 并行 join:Route 和 Itinerary 都完成后才进入 Budget
        // 框架语义:多条 addEdge 同汇入会自动等待所有上游完成
        graph.addEdge("route", "budget");
        graph.addEdge("itinerary", "budget");

        graph.addEdge("budget", "validation");

        // Validation 出口：passed → report, failed → manager
        graph.addConditionalEdges("validation",
                edge_async(state -> state.value(TripPlanningStateKeys.CONTROL_NEXT_NODE)
                        .map(Object::toString)
                        .orElse("report")),
                Map.of(
                        "report", "report",
                        "manager", "manager"
                ));

        // Report → END
        graph.addEdge("report", END);

        return graph.compile();
    }
}
