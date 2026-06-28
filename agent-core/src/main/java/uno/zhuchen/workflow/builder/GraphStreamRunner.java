package uno.zhuchen.workflow.builder;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Graph 流式执行器
 *
 * 把 CompiledGraph.stream() 输出的 NodeOutput 包装为前端 SSE StreamChunk。
 *
 * <p><b>关键设计：</b>整个 Graph 跑在 boundedElastic 线程池上,避免阻塞 Netty event loop。
 * <ul>
 *   <li>Spring AI Alibaba Graph 1.1.2.0 版的 NodeExecutor 会同步调用 NodeAction.apply()</li>
 *   <li>apply() 内部 BaseAgent.doExecute() 包含 LLM 同步调用 (DashScope RestClient)</li>
 *   <li>RestClient 底层是 Reactor Netty,在 reactor-http-nio 线程上不能 block</li>
 *   <li>所以把整个 Graph 执行包到 Mono.fromCallable + subscribeOn(boundedElastic) 中</li>
 *   <li>牺牲了"边跑边推"的细粒度流式,换取 Graph 能在 WebFlux 环境下正确运行</li>
 * </ul>
 *
 * <p>事件序列:
 *   session_init → (branch_taken →) node_start → node_end → ... → final
 */
@Component
public class GraphStreamRunner {

    private final TripPlanningGraphBuilder graphBuilder;

    public GraphStreamRunner(TripPlanningGraphBuilder graphBuilder) {
        this.graphBuilder = graphBuilder;
    }

    /**
     * 执行 Graph 并返回 SSE 流
     *
     * @param rawRequest 用户原始请求
     * @param conversationId 会话 ID（可空,空则生成）
     * @return StreamChunk 流
     */
    public Flux<StreamChunk> runStream(String rawRequest, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }
        final String convId = conversationId;

        // 把整个 Graph 跑在 boundedElastic 线程池上
        // 收集所有事件为 List,然后用 flatMapMany 一次性发射
        return Mono.fromCallable(() -> runGraphAndCollect(rawRequest, convId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * 同步跑完整个 Graph,收集所有事件到 List
     *
     * <p>此方法在 boundedElastic 线程上执行,内部所有阻塞调用都安全
     *
     * @return 事件列表（按时间顺序）
     */
    private List<StreamChunk> runGraphAndCollect(String rawRequest, String conversationId) throws Exception {
        List<StreamChunk> events = new ArrayList<>();
        events.add(StreamChunk.sessionInit(conversationId));

        CompiledGraph graph = graphBuilder.build();

        Map<String, Object> input = new HashMap<>();
        input.put(TripPlanningStateKeys.INPUT_RAW_REQUEST, rawRequest);
        input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, conversationId);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(conversationId)
                .build();

        // 路由追踪（上一个节点名）
        AtomicReference<String> lastNode = new AtomicReference<>("");

        try {
            graph.stream(input, config)
                    .doOnNext(output -> handleNodeOutput(output, conversationId, lastNode, events))
                    .doOnComplete(() -> handleComplete(graph, config, conversationId, events))
                    .doOnError(err -> handleError(err, conversationId, events))
                    .blockLast();  // boundedElastic 线程上 block OK
        } catch (Exception e) {
            handleError(e, conversationId, events);
        }

        return events;
    }

    /**
     * 处理单个 NodeOutput
     */
    private void handleNodeOutput(NodeOutput output, String conversationId,
                                   AtomicReference<String> lastNode, List<StreamChunk> events) {
        String node = output.node();

        // 路由事件（首节点不发）
        String prev = lastNode.getAndSet(node);
        if (prev != null && !prev.isEmpty() && !node.startsWith("__")) {
            events.add(StreamChunk.branchTaken(prev, node, conversationId));
        }

        // 节点开始/结束
        events.add(StreamChunk.nodeStart(node, conversationId));
        events.add(StreamChunk.nodeEnd(node, conversationId, 0L));
    }

    /**
     * Graph 完成时追加 final 事件
     */
    private void handleComplete(CompiledGraph graph, RunnableConfig config,
                                String conversationId, List<StreamChunk> events) {
        try {
            OverAllState state = graph.getState(config).state();
            Map<String, Object> data = state.data();
            String report = data.get(TripPlanningStateKeys.OUTPUT_FINAL_REPORT) != null
                    ? data.get(TripPlanningStateKeys.OUTPUT_FINAL_REPORT).toString()
                    : "(无报告)";
            events.add(StreamChunk.final_(conversationId, report, 0L));
        } catch (Exception e) {
            handleError(e, conversationId, events);
        }
    }

    /**
     * 错误处理
     */
    private void handleError(Throwable err, String conversationId, List<StreamChunk> events) {
        events.add(StreamChunk.error(conversationId, "Graph 执行失败: " + err.getMessage(), 0L));
    }
}
