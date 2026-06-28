package uno.zhuchen.workflow.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.llm.ChatModel;
import uno.zhuchen.agent.tool.ToolRegistry;
import uno.zhuchen.workflow.state.BudgetPlan;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.DayPlan;
import uno.zhuchen.workflow.state.RouteResult;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.state.ValidationReport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告生成 Agent — LLM 驱动。
 *
 * <p>LLM 根据所有信息生成最终 Markdown 报告。</p>
 */
@Component
public class ReportAgent extends BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(ReportAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是旅游规划报告生成助手，负责将所有的旅行规划信息整合成一份美观的 Markdown 报告。

            === 输入 ===
            你会收到所有规划数据：约束、路线、每日行程、预算、校验结果。

            === 输出要求 ===
            生成一份完整的 Markdown 格式旅行报告，包含以下章节：

            # 🌍 [目的地] 旅行计划

            ## 📋 行程概要
            目的地、天数、预算、人数、偏好概览。

            ## 🚗 交通方案
            路线详情：方式、距离、时间、费用。

            ## 📅 每日行程
            每天的活动安排：景点、餐饮、住宿。

            ## 💰 预算明细
            分项费用表、总花费、是否超预算。

            ## ✅ 校验结果
            方案是否通过、警告信息、建议。

            使用 emoji 和表格美化排版。直接输出 Markdown，不要用 JSON 包裹。
            """;

    private final ObjectMapper objectMapper;

    public ReportAgent(ChatModel chatModel, ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        super("ReportAgent", chatModel, toolRegistry);
        this.objectMapper = objectMapper;
    }

    @Override
    protected Map<String, Object> doExecute(OverAllState state) {
        Constraints constraints = findConstraints(state).orElse(null);
        RouteResult route = findRoute(state).orElse(null);
        List<DayPlan> itinerary = findItinerary(state).orElse(List.of());
        BudgetPlan budget = findBudget(state).orElse(null);
        ValidationReport validation = findValidationReport(state).orElse(null);

        log.info("[ReportAgent] LLM 生成报告: destination={}",
                constraints != null ? constraints.getDestination() : "unknown");

        String context = buildContext(state, constraints, route, itinerary, budget, validation);
        String report;
        try {
            report = callLLM(SYSTEM_PROMPT, context);
        } catch (Exception e) {
            log.error("[ReportAgent] LLM 调用失败: {}", e.getMessage());
            report = buildFallbackReport(constraints, route, itinerary, budget, validation);
        }

        if (report == null || report.isBlank()) {
            report = buildFallbackReport(constraints, route, itinerary, budget, validation);
        }

        log.info("[ReportAgent] 报告生成完成,长度 {} 字符", report.length());

        Map<String, Object> result = new HashMap<>();
        result.put(TripPlanningStateKeys.OUTPUT_FINAL_REPORT, report);
        result.put(TripPlanningStateKeys.OUTPUT_STATUS, "success");
        return result;
    }

    private String buildContext(OverAllState state, Constraints c, RouteResult r, List<DayPlan> it,
                                 BudgetPlan b, ValidationReport v) {
        StringBuilder sb = new StringBuilder();

        if (c != null) {
            sb.append(String.format("""
                    === 约束信息 ===
                    目的地: %s
                    天数: %d
                    预算: %d 元
                    同行人数: %d
                    偏好: %s
                    软约束: %s
                    """,
                    c.getDestination(), c.getDays(), c.getBudget(),
                    c.getCompanions(), c.getPreferences(), c.getSoftRequirements()));
        }

        if (r != null && r.getSegments() != null && !r.getSegments().isEmpty()) {
            sb.append("\n=== 路线方案 ===\n");
            for (RouteResult.RouteSegment seg : r.getSegments()) {
                sb.append(String.format("%s: %s → %s (%d元)%n",
                        seg.getMode(), seg.getFrom(), seg.getTo(), seg.getCost()));
            }
        } else {
            state.value(TripPlanningStateKeys.WORKER_ROUTE_RAW)
                    .ifPresent(raw -> sb.append("\n=== 路线方案(原始) ===\n").append(raw).append("\n"));
        }

        if (it != null && !it.isEmpty()) {
            sb.append("\n=== 每日行程 ===\n");
            for (DayPlan day : it) {
                sb.append(String.format("第%d天 (住宿: %s)%n",
                        day.getDayIndex(), day.getAccommodation()));
                if (day.getPois() != null) {
                    for (DayPlan.PoiActivity poi : day.getPois()) {
                        sb.append(String.format("  - %s (%s, %d元)%n",
                                poi.getName(), poi.getType(), poi.getCost()));
                    }
                }
            }
        } else {
            state.value(TripPlanningStateKeys.WORKER_ITINERARY_RAW)
                    .ifPresent(raw -> sb.append("\n=== 每日行程(原始) ===\n").append(raw).append("\n"));
        }

        if (b != null) {
            sb.append(String.format("""
                    
                    === 预算详情 ===
                    总费用: %d 元
                    预算: %d 元
                    超支: %s
                    超出: %d 元
                    分项: %s
                    """,
                    b.getTotalCost(), b.getBudget(),
                    Boolean.TRUE.equals(b.getIsOverBudget()) ? "是" : "否",
                    b.getOverageAmount(), b.getBreakdown()));
        } else {
            state.value(TripPlanningStateKeys.WORKER_BUDGET_RAW)
                    .ifPresent(raw -> sb.append("\n=== 预算详情(原始) ===\n").append(raw).append("\n"));
        }

        if (v != null) {
            sb.append(String.format("""
                    
                    === 校验结果 ===
                    通过: %s
                    失败数: %d
                    警告: %s
                    """,
                    Boolean.TRUE.equals(v.getPassed()) ? "是" : "否",
                    v.getFailures().size(),
                    v.getWarnings()));
        }

        return sb.toString();
    }

    private String buildFallbackReport(Constraints c, RouteResult r, List<DayPlan> it,
                                        BudgetPlan b, ValidationReport v) {
        StringBuilder sb = new StringBuilder();
        String dest = c != null ? c.getDestination() : "未知";
        sb.append("# 🌍 ").append(dest).append(" 旅行计划\n\n");

        sb.append("## 📋 行程概要\n\n");
        if (c != null) {
            sb.append("| 项目 | 内容 |\n|---|---|\n");
            sb.append(String.format("| 目的地 | %s |\n", c.getDestination()));
            sb.append(String.format("| 天数 | %d 天 |\n", c.getDays()));
            sb.append(String.format("| 预算 | %d 元 |\n", c.getBudget()));
            sb.append(String.format("| 人数 | %d 人 |\n", c.getCompanions()));
            if (c.getPreferences() != null && !c.getPreferences().isEmpty()) {
                sb.append(String.format("| 偏好 | %s |\n", String.join(", ", c.getPreferences())));
            }
        }
        sb.append("\n");

        sb.append("## 🚗 交通方案\n\n");
        if (r != null && r.getSegments() != null) {
            sb.append("| 方式 | 出发 | 到达 | 费用 |\n|---|---|---|---|\n");
            for (RouteResult.RouteSegment seg : r.getSegments()) {
                sb.append(String.format("| %s | %s | %s | %d元 |\n",
                        seg.getMode(), seg.getFrom(), seg.getTo(), seg.getCost()));
            }
        } else {
            sb.append("（暂无详细路线信息）\n");
        }
        sb.append("\n");

        sb.append("## 📅 每日行程\n\n");
        if (it != null && !it.isEmpty()) {
            for (DayPlan day : it) {
                sb.append(String.format("### 第%d天\n\n", day.getDayIndex()));
                if (day.getPois() != null && !day.getPois().isEmpty()) {
                    sb.append("| 活动 | 类型 | 费用 |\n|---|---|---|\n");
                    for (DayPlan.PoiActivity poi : day.getPois()) {
                        sb.append(String.format("| %s | %s | %d元 |\n",
                                poi.getName(), poi.getType(), poi.getCost()));
                    }
                }
                if (day.getAccommodation() != null) {
                    sb.append(String.format("\n🏨 **住宿**: %s\n\n", day.getAccommodation()));
                }
            }
        } else {
            sb.append("（暂无详细行程信息）\n");
        }

        sb.append("## 💰 预算明细\n\n");
        if (b != null) {
            sb.append("| 项目 | 金额 |\n|---|---|\n");
            if (b.getBreakdown() != null) {
                b.getBreakdown().forEach((key, val) ->
                        sb.append(String.format("| %s | %d元 |\n", key, val)));
            }
            sb.append(String.format("| **总计** | **%d元** |\n", b.getTotalCost()));
            if (Boolean.TRUE.equals(b.getIsOverBudget())) {
                sb.append(String.format("\n⚠️ **超出预算 %d 元**\n", b.getOverageAmount()));
            }
        } else {
            sb.append("（暂无详细预算信息）\n");
        }
        sb.append("\n");

        sb.append("## ✅ 校验结果\n\n");
        if (v != null) {
            sb.append(Boolean.TRUE.equals(v.getPassed()) ? "✅ **方案通过**" : "❌ **方案未通过**");
            sb.append("\n\n");
            if (v.getWarnings() != null && !v.getWarnings().isEmpty()) {
                sb.append("**警告**:\n");
                for (String w : v.getWarnings()) {
                    sb.append("- ").append(w).append("\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("（暂无校验信息）\n");
        }

        return sb.toString();
    }
}
