package uno.zhuchen.workflow.state;

/**
 * Graph 工作流 next_node 路由枚举 — M2 修复配套.
 *
 * <p>替代散落在 6 个 Agent + GraphBuilder 中的魔法字符串
 * （{@code "worker_group" / "route" / "itinerary" / "budget" / "report" / "first" / "manager"}）。
 *
 * <p>Spring AI Alibaba Graph 的 {@code addConditionalEdges} 仍要求 {@code Map<String, String>} 注册,
 * 因此枚举保留 {@link #key()} 方法返回小写字符串以兼容 Graph 框架.
 *
 * <p>取值约定:
 * <ul>
 *   <li>{@link #MANAGER} — 回退到 ManagerAgent（validation failed / ask_user 重提取）</li>
 *   <li>{@link #WORKER_GROUP} — 进入并行 Worker 组（manager 确认约束后）</li>
 *   <li>{@link #ROUTE} / {@link #ITINERARY} / {@link #BUDGET} — 单 Worker 部分重跑</li>
 *   <li>{@link #VALIDATION} — 进入 ValidationAgent</li>
 *   <li>{@link #REPORT} — 进入 ReportAgent 或 force_passed</li>
 *   <li>{@link #FIRST} — Manager 自环重新提取约束</li>
 * </ul>
 */
public enum NextNode {
    MANAGER,
    WORKER_GROUP,
    ROUTE,
    ITINERARY,
    BUDGET,
    VALIDATION,
    REPORT,
    FIRST;

    /**
     * 返回 Graph 框架所需的小写字符串 key,用于 {@code state[next_node]} 与
     * {@code addConditionalEdges} 注册表.
     */
    public String key() {
        return name().toLowerCase();
    }

    /**
     * 从 state 中读出的字符串反序列化为枚举;未知值返回 {@code null} 而不是抛异常,
     * 让 Graph 框架走 default 分支（next_node = "first" → manager 自环）.
     */
    public static NextNode fromKey(String key) {
        if (key == null) return null;
        try {
            return NextNode.valueOf(key.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}