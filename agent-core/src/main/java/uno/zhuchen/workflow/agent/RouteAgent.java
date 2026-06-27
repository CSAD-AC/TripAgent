package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.workflow.state.RouteResult;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;

import java.util.Map;

/**
 * 路线规划 Agent — Worker 层
 *
 * 调 MCP 路线工具（驾车/步行/骑行/公交/火车），编排高铁/飞机/自驾方案。
 *
 * 输入: Constraints
 * 输出: RouteResult（多个 RouteSegment）
 *
 * Day 2: 空壳 — 返回 mock RouteResult，Day 4 接入真实 MCP 工具
 */
@Component
public class RouteAgent extends BaseAgent {

    public RouteAgent(ChatModel chatModel) {
        super("RouteAgent", chatModel);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        // Day 4 实现: 调 MCP 路线工具
        log.warn("[RouteAgent] Day 2 空壳 — 待 Day 4 实现");
        RouteResult mock = RouteResult.builder()
                .totalCost(500)
                .totalDurationMin(360)
                .summary("[MOCK] 高铁去程 + 高铁返程")
                .build();
        return Map.of(
                TripPlanningStateKeys.WORKER_ROUTE, mock,
                TripPlanningStateKeys.CONTROL_NEXT_NODE, "budget"
        );
    }
}
