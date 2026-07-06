package uno.zhuchen.workflow.builder;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.workflow.agent.BaseAgent;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;
import uno.zhuchen.workflow.state.WorkflowConstants;
import uno.zhuchen.workflow.util.GraphEventEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Graph 流式执行器 — 实时事件推送版
 *
 * <p>使用 Sinks.Many 作为事件管道，Graph 在 boundedElastic 线程上串行执行，
 * 每步结果实时推入 sink。事件模型：
 * <ul>
 *   <li>框架每个节点完成时发射一个 NodeOutput</li>
 *   <li>收到 output 即知道该节点已完成 → 发射 node_status(node, "done")</li>
 *   <li>从 state 读取 next_node 映射到物理节点 → 发射 branch_taken + node_status(next, "running")</li>
 *   <li>最终节点（report）后不发射 running，后续由 graph 框架自然结束</li>
 * </ul>
 *
 * <p>事件序列:
 * <pre>
 *   session_init
 *     → graph_topology
 *     → node_status(manager, "running")        (来自 topology 的 startNode)
 *     → (node_status(node, "done")             (节点完成)
 *        → branch_taken(node, next, condition) (边激活)
 *        → node_status(next, "running"))ⁿ      (下一个节点开始)
 *     → (graph_iteration)?                     (回退时)
 *     → final
 * </pre>
 */
@Component
public class GraphStreamRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphStreamRunner.class);

    private final TripPlanningGraphBuilder graphBuilder;

    public GraphStreamRunner(TripPlanningGraphBuilder graphBuilder) {
        this.graphBuilder = graphBuilder;
    }

    /**
     * 执行 Graph 并返回实时 SSE 流
     */
    public Flux<StreamChunk> runStream(String rawRequest, String conversationId) {
        return runStream(rawRequest, conversationId, UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * 执行 Graph 并返回实时 SSE 流（带 traceId）
     *
     * @param traceId 链路追踪 ID（trace_id 优化 #8），用于贯穿 Graph 执行的所有日志
     *
     * <p>traceId 链路追踪: 本方法签名接收 traceId 后透传, 并通过
     * {@link uno.zhuchen.workflow.state.TripPlanningStateKeys#INPUT_TRACE_ID}
     * 写入 Graph state 供 BaseAgent.apply 读取. 不依赖 MDC/ThreadLocal.
     */
    public Flux<StreamChunk> runStream(String rawRequest, String conversationId, String traceId) {
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        final String convId = conversationId;
        final String trace = traceId;

        // 1. 创建事件 sink（单发射器）
        Sinks.Many<StreamChunk> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 用于异常回调中计算耗时（C5/M9：异步异常也要带真实耗时）
        long startMs = System.currentTimeMillis();

        // 2. 发射前两个事件：会话初始化（携带 traceId）+ 拓扑定义
        safelyEmit(sink, StreamChunk.sessionInit(convId, trace));
        safelyEmit(sink, buildTopologyEvent(convId));

        // 3. 后台线程执行 Graph（boundedElastic 上不阻塞 Netty）
        Mono.fromRunnable(() -> runGraphAndEmit(rawRequest, convId, trace, sink))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null,
                        err -> {
                            log.error("[traceId={}] [GraphStreamRunner] 异步异常: {}", trace, err.getMessage(), err);
                            long asyncDuration = System.currentTimeMillis() - startMs;
                            safelyEmit(sink, StreamChunk.error(convId, "Graph 执行异常: " + err.getMessage(), asyncDuration));
                            sink.tryEmitComplete();
                        },
                        () -> { /* sink 已在 runGraphAndEmit 中完成 */ });

        return sink.asFlux();
    }

    /**
     * 同步执行 Graph 并实时发射事件到 sink
     */
    private void runGraphAndEmit(String rawRequest, String conversationId, String traceId,
                                  Sinks.Many<StreamChunk> sink) {
        // 单一错误发射入口:doOnError / catch / subscribe 三条路径之间用 AtomicBoolean 把关,
        // 避免同一个异常被推三次 error 事件 (C5 修复)
        AtomicBoolean errorEmitted = new AtomicBoolean(false);
        long startMs = System.currentTimeMillis();
        // C1 修复: 实例级 emitter 替代 ThreadLocal,并行节点线程切换时事件不再丢失
        GraphEventEmitter emitter = chunk -> safelyEmit(sink, chunk);
        try {
            CompiledGraph graph = graphBuilder.build(emitter);

            Map<String, Object> input = new HashMap<>();
            input.put(TripPlanningStateKeys.INPUT_RAW_REQUEST, rawRequest);
            input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, conversationId);
            // 写入 state 供 BaseAgent.apply (同步 NodeAction 入口) 读取并拼到 log 中
            input.put(TripPlanningStateKeys.INPUT_TRACE_ID, traceId);

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(conversationId)
                    .build();

            // 初始状态：startNode（manager）开始运行
            safelyEmit(sink, StreamChunk.nodeStatus("manager", "running", conversationId));

            // 迭代跟踪 + 并行 join 计数
            AtomicInteger lastIteration = new AtomicInteger(0);
            AtomicInteger parallelCompletionCount = new AtomicInteger(0);

            // 逐个处理 Graph 节点输出
            graph.stream(input, config)
                    .doOnNext(output -> processOutput(output, conversationId, lastIteration, parallelCompletionCount, sink))
                    .doOnError(err -> {
                        // 不在这里发射 error 事件，统一交给下方 catch 块 (C5 修复)
                        log.debug("[traceId={}] [GraphStreamRunner] Graph stream 异常已上抛: {}",
                                traceId, err.getMessage());
                    })
                    .blockLast();

            // Graph 正常结束
            safelyEmit(sink, StreamChunk.final_(conversationId, extractReport(graph, config, traceId),
                    System.currentTimeMillis() - startMs));

        } catch (Exception e) {
            log.error("[traceId={}] [GraphStreamRunner] Graph 执行异常: {}", traceId, e.getMessage(), e);
            if (errorEmitted.compareAndSet(false, true)) {
                safelyEmit(sink, StreamChunk.error(conversationId,
                        "Graph 执行失败: " + e.getMessage(),
                        System.currentTimeMillis() - startMs));
            }
        } finally {
            sink.tryEmitComplete();
        }
    }

    /**
     * 处理单个节点输出 — 框架在每个节点完成后 emit 一个 NodeOutput
     *
     * <p>处理逻辑（按节点类型分三支）：
     * <ul>
     *   <li>当前节点完成 → {@code node_status(node, "done")}</li>
     *   <li>{@code parallel_group} 完成 → 显式 fan-out 到 route + itinerary,
     *       各发 {@code branch_taken} + {@code node_status(running)}</li>
     *   <li>{@code route} / {@code itinerary} 完成 → 累加计数;达到 2 时对称发两条 join 边
     *       + {@code node_status(budget, running)},count<2 时不发任何 join 事件</li>
     *   <li>其他节点（manager / budget / validation / report）→ 走常规 resolveNextRunning</li>
     *   <li>检测迭代计数变化 → {@code graph_iteration}</li>
     * </ul>
     */
    private void processOutput(NodeOutput output, String conversationId,
                                AtomicInteger lastIteration,
                                AtomicInteger parallelCompletionCount,
                                Sinks.Many<StreamChunk> sink) {
        String node = output.node();
        if (node == null || node.startsWith("__")) return;

        OverAllState outputState = output.state();

        // 0. parallel_group 完成时重置并行计数器,新一轮并行重新计数
        if ("parallel_group".equals(node)) {
            parallelCompletionCount.set(0);
        }

        // 1. 当前节点完成
        safelyEmit(sink, StreamChunk.nodeStatus(node, "done", conversationId));

        // ───────── 分支 A:parallel_group 完成 → fan-out 两路并行 ─────────
        if ("parallel_group".equals(node)) {
            // 两条 fan-out 边同时点亮,前端 DAG 立即可见
            safelyEmit(sink, StreamChunk.branchTaken("parallel_group", "route", "fanout_route", conversationId));
            safelyEmit(sink, StreamChunk.nodeStatus("route", "running", conversationId));
            safelyEmit(sink, StreamChunk.branchTaken("parallel_group", "itinerary", "fanout_itinerary", conversationId));
            safelyEmit(sink, StreamChunk.nodeStatus("itinerary", "running", conversationId));
            // fan-out 跳过常规 next-node 解析,直接返回
            emitIterationIfChanged(outputState, lastIteration, conversationId, sink);
            return;
        }

        // ───────── 分支 B:并行节点 done(route / itinerary) → join 判定 ─────────
        boolean isParallelNode = "route".equals(node) || "itinerary".equals(node);
        if (isParallelNode) {
            parallelCompletionCount.incrementAndGet();
            if (parallelCompletionCount.get() >= 2) {
                // 两边都完成,触发 join:对称发两条 join 边 + budget running
                safelyEmit(sink, StreamChunk.branchTaken("route", "budget", "joined", conversationId));
                safelyEmit(sink, StreamChunk.branchTaken("itinerary", "budget", "joined", conversationId));
                safelyEmit(sink, StreamChunk.nodeStatus("budget", "running", conversationId));
            }
            // count<2 时不发任何 join/budget 事件,等待另一边
            return;
        }

        // ───────── 分支 C:其他节点 → 常规 next-node 路由 ─────────
        String nextPhysical = resolveNextRunning(node, outputState);
        if (!nextPhysical.isEmpty()) {
            String condition = resolveConditionLabel(node, nextPhysical, outputState);
            safelyEmit(sink, StreamChunk.branchTaken(node, nextPhysical, condition, conversationId));
            safelyEmit(sink, StreamChunk.nodeStatus(nextPhysical, "running", conversationId));
        }

        // iteration 跟踪（Manager 回退场景）
        emitIterationIfChanged(outputState, lastIteration, conversationId, sink);
    }

    /**
     * 检测 iteration_count 变化并发射 graph_iteration 事件
     */
    private void emitIterationIfChanged(OverAllState outputState, AtomicInteger lastIteration,
                                         String conversationId, Sinks.Many<StreamChunk> sink) {
        if (outputState == null) return;
        int currentIteration = outputState.value(TripPlanningStateKeys.CONTROL_ITERATION_COUNT)
                .map(v -> ((Number) v).intValue())
                .orElse(0);
        if (currentIteration > lastIteration.get()) {
            lastIteration.set(currentIteration);
            String reason = outputState.value(TripPlanningStateKeys.CONTROL_WARNINGS)
                    .map(Object::toString)
                    .orElse("第 " + currentIteration + " 次回退");
            safelyEmit(sink, StreamChunk.graphIteration(
                    currentIteration, WorkflowConstants.MAX_ITERATIONS, reason, conversationId));
        }
    }

    /**
     * 从当前节点 + state.next_node 确定下一个物理节点
     *
     * <p>对固定边直接返回；对条件边（manager / validation）查 state。
     * <p>并行节点（route / itinerary）的 join 由调用方根据 parallelCompletionCount 控制,
     * 本方法总是返回 "budget" 让框架语义正确,调用方在未达到 join 阈值时不会发射 running 事件。
     */
    private String resolveNextRunning(String fromNode, OverAllState state) {
        // ── 固定边 ──
        switch (fromNode) {
            case "route":     return "budget";   // join 到 budget,实际触发由调用方根据并行计数控制
            case "itinerary": return "budget";   // 同上
            case "budget":    return "validation";
            case "report":    return ""; // → END
        }

        // ── 条件边：需要读 state.next_node ──
        if (state == null) return "";
        String nextNode = state.value(TripPlanningStateKeys.CONTROL_NEXT_NODE)
                .map(Object::toString)
                .orElse("");

        if ("manager".equals(fromNode)) {
            return switch (nextNode) {
                case "worker_group" -> "parallel_group";  // 虚拟 fan-out 入口
                case "route"        -> "route";            // 部分重跑 route
                case "itinerary"    -> "itinerary";        // 部分重跑 itinerary
                case "budget"       -> "budget";
                case "first"        -> "manager";
                case "report"       -> "report";
                default -> "";
            };
        }
        if ("validation".equals(fromNode)) {
            return "report".equals(nextNode) ? "report" : "manager";
        }
        return "";
    }

    /**
     * 确定条件边标签（如 "confirmed" / "passed" / "failed" 等）
     */
    private String resolveConditionLabel(String from, String to, OverAllState state) {
        if ("manager".equals(from)) {
            if (state == null) return "";
            String nextNode = state.value(TripPlanningStateKeys.CONTROL_NEXT_NODE)
                    .map(Object::toString)
                    .orElse("");
            return switch (nextNode) {
                case "worker_group" -> "confirmed";
                case "route"        -> "retry_route";
                case "itinerary"    -> "retry_itinerary";
                case "budget"       -> "retry_budget";
                case "first"        -> "reject";
                case "report"       -> "give_up";
                default -> nextNode;
            };
        }
        if ("validation".equals(from)) {
            return "report".equals(to) ? "passed" : "failed";
        }
        if ("parallel_group".equals(from)) {
            // fan-out 到 route / itinerary 两个分支都是无条件触发
            return "route".equals(to) ? "fanout_route" : "fanout_itinerary";
        }
        // route / itinerary → budget 的 join 边
        if ("route".equals(from) || "itinerary".equals(from)) {
            return "joined";
        }
        return "";
    }

    /**
     * 从 Graph state 提取最终报告
     */
    private String extractReport(CompiledGraph graph, RunnableConfig config, String traceId) {
        try {
            OverAllState state = graph.getState(config).state();
            Object report = state.value(TripPlanningStateKeys.OUTPUT_FINAL_REPORT).orElse(null);
            return report != null ? report.toString() : "(无报告)";
        } catch (Exception e) {
            log.warn("[traceId={}] [GraphStreamRunner] 提取报告失败: {}", traceId, e.getMessage());
            return "(报告提取失败)";
        }
    }

    // ============ 拓扑定义 ============

    /**
     * 构建 graph_topology 事件
     *
     * <p>拓扑结构与 {@link TripPlanningGraphBuilder} 保持同步（并行版）：
     * manager → parallel_group → {route, itinerary} → budget → validation → report
     */
    private StreamChunk buildTopologyEvent(String conversationId) {
        List<Map<String, Object>> nodes = new ArrayList<>();

        nodes.add(nodeDef("manager",        "主管",       "supervisor", "提取需求约束，决策回退/重试"));
        nodes.add(nodeDef("parallel_group", "并行中转",   "fanout",     "虚拟 fan-out 节点,触发 Route + Itinerary 并行"));
        nodes.add(nodeDef("route",          "路线",       "worker",     "规划交通路线方案"));
        nodes.add(nodeDef("itinerary",      "行程",       "worker",     "编排每日景点和活动"));
        nodes.add(nodeDef("budget",         "预算",       "worker",     "精算分解各项费用"));
        nodes.add(nodeDef("validation",     "校验",       "validator",  "检查预算/约束是否满足"));
        nodes.add(nodeDef("report",         "报告",       "report",     "生成最终旅行报告"));

        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edgeDef("__start__",       "manager",        "",        List.of()));
        edges.add(edgeDef("manager",         "parallel_group", "确认",    List.of("worker_group")));
        edges.add(edgeDef("manager",         "route",          "重试路线", List.of("route")));
        edges.add(edgeDef("manager",         "itinerary",      "重试行程", List.of("itinerary")));
        edges.add(edgeDef("manager",         "budget",         "重试预算", List.of("budget")));
        edges.add(edgeDef("manager",         "manager",        "重提取",  List.of("first")));
        edges.add(edgeDef("manager",         "report",         "放弃",    List.of("report")));
        // parallel_group fan-out:无条件下发到 route 和 itinerary
        edges.add(edgeDef("parallel_group", "route",          "fan-out",  List.of()));
        edges.add(edgeDef("parallel_group", "itinerary",      "fan-out",  List.of()));
        // route / itinerary → budget 并行 join
        edges.add(edgeDef("route",          "budget",         "join",     List.of()));
        edges.add(edgeDef("itinerary",      "budget",         "join",     List.of()));
        edges.add(edgeDef("budget",         "validation",     "",         List.of()));
        edges.add(edgeDef("validation",     "report",         "通过",     List.of("report")));
        edges.add(edgeDef("validation",     "manager",        "失败",     List.of("manager")));
        edges.add(edgeDef("report",         "__end__",        "",         List.of()));

        return StreamChunk.graphTopology(nodes, edges, "manager", "report", conversationId);
    }

    private static Map<String, Object> nodeDef(String id, String label, String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> edgeDef(String from, String to, String label, List<String> conditions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        m.put("label", label);
        m.put("conditions", conditions);
        return m;
    }

    // ============ 安全发射 ============

    private static void safelyEmit(Sinks.Many<StreamChunk> sink, StreamChunk event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result != Sinks.EmitResult.OK) {
            log.warn("[GraphStreamRunner] 事件发射失败: type={}, result={}", event.getType(), result);
        }
    }
}
