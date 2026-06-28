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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static final int MAX_ITERATIONS = 3;

    private final TripPlanningGraphBuilder graphBuilder;

    public GraphStreamRunner(TripPlanningGraphBuilder graphBuilder) {
        this.graphBuilder = graphBuilder;
    }

    /**
     * 执行 Graph 并返回实时 SSE 流
     */
    public Flux<StreamChunk> runStream(String rawRequest, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        final String convId = conversationId;

        // 1. 创建事件 sink（单发射器）
        Sinks.Many<StreamChunk> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 2. 发射前两个事件：会话初始化 + 拓扑定义
        safelyEmit(sink, StreamChunk.sessionInit(convId));
        safelyEmit(sink, buildTopologyEvent(convId));

        // 3. 后台线程执行 Graph（boundedElastic 上不阻塞 Netty）
        Mono.fromRunnable(() -> runGraphAndEmit(rawRequest, convId, sink))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null,
                        err -> {
                            log.error("[GraphStreamRunner] 异步异常: {}", err.getMessage(), err);
                            safelyEmit(sink, StreamChunk.error(convId, "Graph 执行异常: " + err.getMessage(), 0L));
                            sink.tryEmitComplete();
                        },
                        () -> { /* sink 已在 runGraphAndEmit 中完成 */ });

        return sink.asFlux();
    }

    /**
     * 同步执行 Graph 并实时发射事件到 sink
     */
    private void runGraphAndEmit(String rawRequest, String conversationId, Sinks.Many<StreamChunk> sink) {
        try {
            // 设置当前线程的事件发射器，供 Agent 内部（callLLMWithTools）使用
            BaseAgent.setEventEmitterForThread(chunk -> safelyEmit(sink, chunk));

            CompiledGraph graph = graphBuilder.build();

            Map<String, Object> input = new HashMap<>();
            input.put(TripPlanningStateKeys.INPUT_RAW_REQUEST, rawRequest);
            input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, conversationId);

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(conversationId)
                    .build();

            // 初始状态：startNode（manager）开始运行
            safelyEmit(sink, StreamChunk.nodeStatus("manager", "running", conversationId));

            // 迭代跟踪
            AtomicInteger lastIteration = new AtomicInteger(0);

            // 逐个处理 Graph 节点输出
            graph.stream(input, config)
                    .doOnNext(output -> processOutput(output, conversationId, lastIteration, sink))
                    .doOnError(err -> {
                        log.error("[GraphStreamRunner] Graph stream 异常: {}", err.getMessage(), err);
                        safelyEmit(sink, StreamChunk.error(conversationId,
                                "Graph stream 异常: " + err.getMessage(), 0L));
                    })
                    .blockLast();

            // Graph 正常结束
            safelyEmit(sink, StreamChunk.final_(conversationId, extractReport(graph, config), 0L));

        } catch (Exception e) {
            log.error("[GraphStreamRunner] Graph 执行异常: {}", e.getMessage(), e);
            safelyEmit(sink, StreamChunk.error(conversationId, "Graph 执行失败: " + e.getMessage(), 0L));
        } finally {
            BaseAgent.clearEventEmitterForThread();
            sink.tryEmitComplete();
        }
    }

    /**
     * 处理单个节点输出 — 框架在每个节点完成后 emit 一个 NodeOutput
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>该节点已完成 → {@code node_status(node, "done")}</li>
     *   <li>读 state.next_node 映射到物理节点 → {@code branch_taken(node, next)}</li>
     *   <li>若下一个不是终止节点 → {@code node_status(next, "running")}</li>
     *   <li>检测迭代计数变化 → {@code graph_iteration}</li>
     * </ol>
     */
    private void processOutput(NodeOutput output, String conversationId,
                                AtomicInteger lastIteration,
                                Sinks.Many<StreamChunk> sink) {
        String node = output.node();
        if (node == null || node.startsWith("__")) return;

        OverAllState outputState = output.state();

        // 1. 当前节点完成
        safelyEmit(sink, StreamChunk.nodeStatus(node, "done", conversationId));

        // 2. 确定下一个物理节点
        String nextPhysical = resolveNextRunning(node, outputState);

        // 3. 发射边事件 + 下一个节点开始（非终态）
        if (!nextPhysical.isEmpty()) {
            String condition = resolveConditionLabel(node, nextPhysical, outputState);
            safelyEmit(sink, StreamChunk.branchTaken(node, nextPhysical, condition, conversationId));
            safelyEmit(sink, StreamChunk.nodeStatus(nextPhysical, "running", conversationId));
        }

        // 4. 迭代跟踪（Manager 回退场景）
        if (outputState != null) {
            int currentIteration = outputState.value(TripPlanningStateKeys.CONTROL_ITERATION_COUNT)
                    .map(v -> ((Number) v).intValue())
                    .orElse(0);
            if (currentIteration > lastIteration.get()) {
                lastIteration.set(currentIteration);
                String reason = outputState.value(TripPlanningStateKeys.CONTROL_WARNINGS)
                        .map(Object::toString)
                        .orElse("第 " + currentIteration + " 次回退");
                safelyEmit(sink, StreamChunk.graphIteration(
                        currentIteration, MAX_ITERATIONS, reason, conversationId));
            }
        }
    }

    /**
     * 从当前节点 + state.next_node 确定下一个物理节点
     *
     * <p>对固定边（route→itinerary 等）直接返回；对条件边（manager / validation）查 state。
     */
    private String resolveNextRunning(String fromNode, OverAllState state) {
        // ── 固定边 ──
        switch (fromNode) {
            case "route":     return "itinerary";
            case "itinerary": return "budget";
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
                case "worker_group", "route" -> "route";
                case "itinerary" -> "itinerary";
                case "budget"    -> "budget";
                case "first"     -> "manager";
                case "report"    -> "report";
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
                case "worker_group", "route" -> "confirmed";
                case "itinerary" -> "retry_itinerary";
                case "budget" -> "retry_budget";
                case "first" -> "reject";
                case "report" -> "give_up";
                default -> nextNode;
            };
        }
        if ("validation".equals(from)) {
            return "report".equals(to) ? "passed" : "failed";
        }
        return "";
    }

    /**
     * 从 Graph state 提取最终报告
     */
    private String extractReport(CompiledGraph graph, RunnableConfig config) {
        try {
            OverAllState state = graph.getState(config).state();
            Object report = state.value(TripPlanningStateKeys.OUTPUT_FINAL_REPORT).orElse(null);
            return report != null ? report.toString() : "(无报告)";
        } catch (Exception e) {
            log.warn("[GraphStreamRunner] 提取报告失败: {}", e.getMessage());
            return "(报告提取失败)";
        }
    }

    // ============ 拓扑定义 ============

    /**
     * 构建 graph_topology 事件
     *
     * <p>拓扑结构与 {@link TripPlanningGraphBuilder} 保持同步。
     */
    private StreamChunk buildTopologyEvent(String conversationId) {
        List<Map<String, Object>> nodes = new ArrayList<>();

        nodes.add(nodeDef("manager",    "主管",   "supervisor", "提取需求约束，决策回退/重试"));
        nodes.add(nodeDef("route",      "路线",   "worker",    "规划交通路线方案"));
        nodes.add(nodeDef("itinerary",  "行程",   "worker",    "编排每日景点和活动"));
        nodes.add(nodeDef("budget",     "预算",   "worker",    "精算分解各项费用"));
        nodes.add(nodeDef("validation", "校验",   "validator", "检查预算/约束是否满足"));
        nodes.add(nodeDef("report",     "报告",   "report",    "生成最终旅行报告"));

        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edgeDef("__start__", "manager",    "",       List.of()));
        edges.add(edgeDef("manager",   "route",      "确认",   List.of("worker_group", "route")));
        edges.add(edgeDef("manager",   "itinerary",  "重试行程", List.of("itinerary")));
        edges.add(edgeDef("manager",   "budget",     "重试预算", List.of("budget")));
        edges.add(edgeDef("manager",   "manager",    "重提取",  List.of("first")));
        edges.add(edgeDef("manager",   "report",     "放弃",   List.of("report")));
        edges.add(edgeDef("route",     "itinerary",  "",       List.of()));
        edges.add(edgeDef("itinerary", "budget",     "",       List.of()));
        edges.add(edgeDef("budget",    "validation", "",       List.of()));
        edges.add(edgeDef("validation","report",     "通过",   List.of("report")));
        edges.add(edgeDef("validation","manager",    "失败",   List.of("manager")));
        edges.add(edgeDef("report",    "__end__",    "",       List.of()));

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
