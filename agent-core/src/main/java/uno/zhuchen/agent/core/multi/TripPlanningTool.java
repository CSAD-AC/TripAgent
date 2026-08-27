package uno.zhuchen.agent.core.multi;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.workflow.builder.TripPlanningGraphBuilder;
import uno.zhuchen.workflow.state.TripPlanningStateKeys;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 旅游规划工具 — 把 Graph 工作流封装为 ToolCallback(Graph-as-Tool)
 *
 * <p>实现 Multi-Agent 模式的核心卖点: ManagerAgent 可把复杂规划"外包"给
 * SWV 流水线(约束提取 → 路线 → 行程 → 预算 → 校验 → 报告), 形成
 * "自主调度 + 固定流水线"的混合编排.
 *
 * <p>方案 C 透明化: 实现 {@link TransparentAgentTool}, 把 Graph 内部节点事件
 * (node_status / node_progress / branch_taken / graph_iteration / node_error)
 * 实时转发为 node=tripPlanner 命名空间的 node_progress 上抛, 用户在主管面板
 * 即可看到规划流水线的推进过程(此前是 no-op 全丢弃的黑盒).
 *
 * <p>同步阻塞: {@link #callTransparent} 内部 {@code graph.stream(...).blockLast()}
 * 同步跑完整个 Graph, 由调用方(MultiAgentManager)的 boundedElastic 线程承载, 不阻塞 Netty.
 *
 * <p>上下文传递: ManagerAgent 调用 {@link #callTransparent} 显式传入 conversationId /
 * traceId / progress; modelId 参数保留但内部未用(Graph 流水线暂用注册表默认模型).
 */
@Component
public class TripPlanningTool implements ToolCallback, TransparentAgentTool {

    private static final Logger log = LoggerFactory.getLogger(TripPlanningTool.class);

    /** 转发到主管面板的命名空间节点名, 与子代理名/Graph 节点名区分 */
    public static final String NODE_TRIP_PLANNER = "tripPlanner";

    private final TripPlanningGraphBuilder graphBuilder;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public TripPlanningTool(@Lazy TripPlanningGraphBuilder graphBuilder, ObjectMapper objectMapper) {
        this.graphBuilder = graphBuilder;
        this.objectMapper = objectMapper;
        this.definition = ToolDefinition.builder()
                .name("tripPlanner")
                .description("""
                        旅游规划专家 — 针对多天/多城市/预算受限的完整旅游规划, 调用内部 SWV 流水线
                        (约束提取 → 路线 → 行程 → 预算 → 校验 → 报告), 返回完整旅行规划报告。
                        适合: 想去 X 玩 N 天, 预算 M, K 个人, 喜欢 A/B。
                        仅当任务需要完整规划(含路线/每日行程/预算)时使用; 单点查询请用其他子代理。
                        """)
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "request": {
                              "type": "string",
                              "description": "完整的旅游规划需求描述, 包含目的地/天数/预算/人数/偏好等"
                            }
                          },
                          "required": ["request"]
                        }
                        """)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return callTransparent(toolInput, null, null, null, null);
    }

    /**
     * 带会话上下文 + 进度回调的调用入口(方案 C)
     *
     * @param progress 进度回调(转发 Graph 内部节点事件; 可为 null)
     */
    @Override
    public String callTransparent(String toolInput, String conversationId, String traceId,
                                  String modelId, Consumer<StreamChunk> progress) {
        String request = parseRequest(toolInput);
        if (request == null || request.isBlank()) {
            return "错误: tripPlanner 缺少 request 参数";
        }
        String convId = conversationId != null ? conversationId : UUID.randomUUID().toString();
        String trace = traceId != null ? traceId : UUID.randomUUID().toString().substring(0, 8);
        long start = System.currentTimeMillis();
        try {
            // 方案 C: 显式注入转发 emitter, 把 Graph 内部节点事件上抛给主管面板
            CompiledGraph graph = graphBuilder.build(chunk -> forwardGraphEvent(chunk, convId, progress));

            Map<String, Object> input = new HashMap<>();
            input.put(TripPlanningStateKeys.INPUT_RAW_REQUEST, request);
            input.put(TripPlanningStateKeys.INPUT_CONVERSATION_ID, convId);
            input.put(TripPlanningStateKeys.INPUT_TRACE_ID, trace);

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(convId)
                    .build();

            graph.stream(input, config).blockLast();

            OverAllState state = graph.getState(config).state();
            Object report = state.value(TripPlanningStateKeys.OUTPUT_FINAL_REPORT).orElse(null);
            long duration = System.currentTimeMillis() - start;
            log.info("[traceId={}] [TripPlanningTool] Graph 规划完成, 耗时 {}ms, 报告长度={}",
                    trace, duration, report != null ? report.toString().length() : 0);
            return report != null ? report.toString() : "(规划执行完成但没有生成报告)";
        } catch (Exception e) {
            log.error("[traceId={}] [TripPlanningTool] Graph 执行失败: {}", trace, e.getMessage(), e);
            return "旅游规划执行失败: " + e.getMessage();
        }
    }

    /**
     * 把 Graph 内部节点事件转发为 tripPlanner 命名空间的 node_progress.
     *
     * <p>映射规则:
     * <ul>
     *   <li>node_status  → progressType=node_status, content="node: status"</li>
     *   <li>node_progress→ 保留原 progressType, content 前缀 "[node]"</li>
     *   <li>branch_taken → progressType=branch, content="from -> to (condition)"</li>
     *   <li>node_error   → progressType=error, content="node: msg"</li>
     *   <li>graph_iteration → progressType=graph_iteration, content="第 X 轮: reason"</li>
     *   <li>node_data    → 忽略(数据量大, 不作为进度展示)</li>
     * </ul>
     */
    private void forwardGraphEvent(StreamChunk chunk, String conversationId,
                                   Consumer<StreamChunk> progress) {
        if (progress == null || chunk == null) {
            return;
        }
        switch (chunk.getType()) {
            case StreamChunk.TYPE_NODE_STATUS -> emit(progress, conversationId, "node_status",
                    chunk.getNode() + ": " + chunk.getNodeStatus());
            case StreamChunk.TYPE_NODE_PROGRESS -> emit(progress, conversationId,
                    chunk.getProgressType() != null ? chunk.getProgressType() : "progress",
                    (chunk.getNode() != null ? "[" + chunk.getNode() + "] " : "") + safe(chunk.getContent()));
            case StreamChunk.TYPE_BRANCH_TAKEN -> emit(progress, conversationId, "branch",
                    chunk.getFrom() + " -> " + chunk.getTo()
                            + (chunk.getCondition() != null && !chunk.getCondition().isEmpty()
                                ? " (" + chunk.getCondition() + ")" : ""));
            case "node_error" -> emit(progress, conversationId, "error",
                    (chunk.getNode() != null ? chunk.getNode() + ": " : "") + safe(chunk.getContent()));
            case StreamChunk.TYPE_GRAPH_ITERATION -> emit(progress, conversationId, "graph_iteration",
                    "第 " + chunk.getIterationCount() + "/" + chunk.getMaxIterations() + " 轮重试: "
                            + safe(chunk.getContent()));
            default -> { /* node_data 及其他事件不转发 */ }
        }
    }

    private void emit(Consumer<StreamChunk> progress, String conversationId,
                      String progressType, String content) {
        progress.accept(StreamChunk.nodeProgress(NODE_TRIP_PLANNER, progressType, content,
                conversationId == null ? "" : conversationId));
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    private String parseRequest(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            JsonNode request = node.get("request");
            return request != null ? request.asText() : "";
        } catch (Exception e) {
            return toolInput.trim();
        }
    }
}
