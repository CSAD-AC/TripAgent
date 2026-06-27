package uno.zhuchen.workflow.state;

/**
 * TripPlanningState 的状态键常量定义
 *
 * 所有状态键集中在此处定义，避免散落在各个 Agent 中产生拼写错误。
 * 每个 key 对应 OverAllState 中的一项数据。
 *
 * 键的语义分组:
 * - INPUT_* — 用户原始输入
 * - CONSTRAINTS_* — 主管提取的结构化约束
 * - WORKER_* — Worker 层输出
 * - VALIDATION_* — 校验层输出
 * - CONTROL_* — 控制流相关（next_node / iteration_count / warnings）
 * - OUTPUT_* — 终态输出
 */
public final class TripPlanningStateKeys {

    private TripPlanningStateKeys() {
        // 工具类，不允许实例化
    }

    // ============ INPUT 输入 ============

    /** 用户原始请求文本 */
    public static final String INPUT_RAW_REQUEST = "raw_request";

    /** 会话 ID（贯穿整个 Graph 生命周期） */
    public static final String INPUT_CONVERSATION_ID = "conversation_id";

    /** Trace ID（用于日志链路追踪） */
    public static final String INPUT_TRACE_ID = "trace_id";

    // ============ CONSTRAINTS 约束（ManagerAgent 输出）============

    /** 提取的结构化约束对象 {@link Constraints} */
    public static final String CONSTRAINTS = "constraints";

    // ============ WORKER Worker 层输出 ============

    /** 路线规划结果 {@link RouteResult} */
    public static final String WORKER_ROUTE = "route";

    /** 行程编排结果 {@code List<DayPlan>} */
    public static final String WORKER_ITINERARY = "itinerary";

    /** 预算精算结果 {@link BudgetPlan} */
    public static final String WORKER_BUDGET = "budget";

    // ============ VALIDATION 校验层输出 ============

    /** 校验报告 {@link ValidationReport} */
    public static final String VALIDATION_REPORT = "validation_report";

    // ============ CONTROL 控制流 ============

    /**
     * 下一节点路由标识 — 由节点内部决定 next_node 取值，
     * addConditionalEdges 据此路由。
     *
     * 取值约定:
     * - "manager" — 回退到 ManagerAgent
     * - "parallel_group" — 进入并行 Worker 组
     * - "route" / "itinerary" / "budget" — 单独 Worker
     * - "validation" — 进入 ValidationAgent
     * - "report" — 进入 ReportAgent
     * - "end" — 结束
     */
    public static final String CONTROL_NEXT_NODE = "next_node";

    /** 迭代计数（首次为 0，每次回退 +1，上限 2） */
    public static final String CONTROL_ITERATION_COUNT = "iteration_count";

    /** 警告信息列表（AppendStrategy） */
    public static final String CONTROL_WARNINGS = "warnings";

    // ============ OUTPUT 终态 ============

    /** 最终报告（终态填充） */
    public static final String OUTPUT_FINAL_REPORT = "final_report";

    /** 执行状态（success / failed / partial） */
    public static final String OUTPUT_STATUS = "status";
}
