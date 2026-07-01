package uno.zhuchen.workflow.state;

/**
 * TripPlanning Graph 工作流常量定义
 *
 * <p>集中托管跨节点共享的工作流级常量，避免散落在 Agent / GraphRunner 中
 * 产生"同义不同值"的语义不一致。
 */
public final class WorkflowConstants {

    private WorkflowConstants() {
        // 工具类，不允许实例化
    }

    /**
     * 允许的最大回退次数。
     * <p>语义：ValidationAgent 失败后，ManagerAgent 最多可以"重试"本次规划
     * {@value} 次（即连续触发 worker_group → ... → validation 循环 2 轮）。
     */
    public static final int MAX_RETRIES = 2;

    /**
     * iteration_count 的最大允许值。
     * <p>等于 {@code MAX_RETRIES + 1}：首次执行为 0，每次回退 +1，达到本上限后
     * ManagerAgent 强制将 next_node 设为 "report" 以结束 Graph。
     * <p>该值同时用于 {@code graph_iteration} 事件向用户展示回退进度上限。
     */
    public static final int MAX_ITERATIONS = MAX_RETRIES + 1;
}
