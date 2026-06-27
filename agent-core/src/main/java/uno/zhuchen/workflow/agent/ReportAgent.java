package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;

import java.util.Map;

/**
 * 报告 Agent — 终态
 *
 * 汇总 Route + Itinerary + Budget + ValidationReport，
 * 输出 Markdown 格式的完整旅游规划报告。
 *
 * 输入: 全套 state
 * 输出: finalReport + status=success
 *
 * Day 2: 空壳 — 输出占位 Markdown，Day 6 完整拼接
 */
@Component
public class ReportAgent extends BaseAgent {

    public ReportAgent(ChatModel chatModel) {
        super("ReportAgent", chatModel);
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        log.warn("[ReportAgent] Day 2 空壳 — 待 Day 6 实现");

        String mockReport = """
                # 旅游规划报告 [MOCK]

                ## 行程
                待 Day 6 拼接完整 Markdown
                """;

        return Map.of(
                TripPlanningStateKeys.OUTPUT_FINAL_REPORT, mockReport,
                TripPlanningStateKeys.OUTPUT_STATUS, "success"
        );
    }
}
