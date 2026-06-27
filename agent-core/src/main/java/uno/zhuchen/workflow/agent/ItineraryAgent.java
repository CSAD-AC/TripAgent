package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.workflow.state.DayPlan;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;

import java.util.List;
import java.util.Map;

/**
 * 行程编排 Agent — Worker 层
 *
 * 调 MCP 天气 + POI 工具，按天编排景点。
 * 输入含 softRequirements（来自 Constraints），尽量覆盖个性化需求。
 *
 * 输入: Constraints（含 softRequirements）
 * 输出: List<DayPlan>
 *
 * Day 2: 空壳 — 返回 mock 行程，Day 4 接入真实 MCP 工具 + Day 5 实现降级模式
 */
@Component
public class ItineraryAgent extends BaseAgent {

    public ItineraryAgent(ChatModel chatModel) {
        super("ItineraryAgent", chatModel);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        // Day 4 实现: 调 MCP 天气+POI
        log.warn("[ItineraryAgent] Day 2 空壳 — 待 Day 4 实现");
        DayPlan day1 = DayPlan.builder()
                .dayIndex(1)
                .weather("[MOCK] 晴 25°C")
                .dining("[MOCK] 烤鸭")
                .build();
        return Map.of(
                TripPlanningStateKeys.WORKER_ITINERARY, List.of(day1),
                TripPlanningStateKeys.CONTROL_NEXT_NODE, "budget"
        );
    }
}
