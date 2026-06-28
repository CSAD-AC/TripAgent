package uno.zhuchen.agent.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 流式响应块 — SSE 模式下逐块推送给前端
 *
 * 事件类型按场景分组:
 * - ReAct 模式: thinking_token / tool_call / tool_result / tool_error / iteration_separator / final
 * - Graph 模式: graph_topology → node_status → (node_progress → node_data)ⁿ → final
 * - 通用: session_init / clarification_request / heartbeat / error
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StreamChunk {

    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_FINAL = "final";
    public static final String TYPE_ERROR = "error";

    /** 全流程流式新增事件类型 */
    public static final String TYPE_THINKING_TOKEN = "thinking_token";
    public static final String TYPE_TOOL_CALL_START = "tool_call_start";
    public static final String TYPE_TOOL_ERROR = "tool_error";
    public static final String TYPE_ITERATION_SEPARATOR = "iteration_separator";

    /** 反问事件 — 通知前端弹出问题卡 */
    public static final String TYPE_CLARIFICATION_REQUEST = "clarification_request";

    /** 会话初始化事件 — 流的第一个事件,告知前端当前 conversationId */
    public static final String TYPE_SESSION_INIT = "session_init";

    /** 心跳事件 — 反问阻塞期间定期推送,防止反向代理 timeout */
    public static final String TYPE_HEARTBEAT = "heartbeat";

    // ============ Graph 工作流新增事件（Phase 3 Day 6）============

    /** 节点开始执行 */
    public static final String TYPE_NODE_START = "node_start";

    /** 节点执行结束 */
    public static final String TYPE_NODE_END = "node_end";

    /** 节点执行警告（如软约束未满足,触发回退） */
    public static final String TYPE_NODE_WARNING = "node_warning";

    /** 条件边选择（用于前端可视化 Graph 流向） */
    public static final String TYPE_BRANCH_TAKEN = "branch_taken";

    // ============ Graph 流可视化新增事件（Phase 3 Day 7）============

    /** Graph 拓扑定义 — 流的第 2 个事件（紧跟 session_init），描述 DAG 结构 */
    public static final String TYPE_GRAPH_TOPOLOGY = "graph_topology";

    /** 节点状态变化 — lifecycle: queued / running / completing / done / error / skipped */
    public static final String TYPE_NODE_STATUS = "node_status";

    /** 节点内部进展 — 如 LLM 思考片段 / 工具调用中间结果 */
    public static final String TYPE_NODE_PROGRESS = "node_progress";

    /** 节点输出数据 — 携带结构化 payload（route/budget/constraints 等） */
    public static final String TYPE_NODE_DATA = "node_data";

    /** 迭代跟踪 — Manager 回退决策时发射，前端显示 "第 X 轮重试" */
    public static final String TYPE_GRAPH_ITERATION = "graph_iteration";

    /** 事件类型：thinking / tool_call / tool_result / final / error */
    private String type;

    /** 文本内容或错误消息 */
    private String content;

    /** 会话 ID */
    private String conversationId;

    /** 工具名称（tool_call / tool_result 事件有效） */
    private String toolName;

    /** 工具参数 JSON（tool_call 事件有效） */
    private String toolArguments;

    /** 工具结果摘要（tool_result 事件有效） */
    private String toolResult;

    /** 迭代信息（thinking_token 事件有效） */
    private String iterationInfo;

    /** 耗时（ms），仅 final/error 事件有效 */
    private long durationMs;

    /** 反问问题 ID（clarification_request 事件有效），前端用它来回传答案 */
    private String questionId;

    /** 是否允许自定义输入（clarification_request 事件有效） */
    private Boolean allowCustom;

    // ============ Graph 流可视化新增字段 ============

    /** 节点名称（node_status / node_progress / node_data 事件有效） */
    private String node;

    /** 节点状态（node_status 事件有效: queued / running / completing / done / error / skipped） */
    private String nodeStatus;

    /** 数据类型（node_data 事件有效: constraints / route / dayplans / budget / validation / report / decision） */
    private String dataType;

    /** 复杂数据负载（node_data / graph_topology 事件有效，JSON 对象） */
    private Map<String, Object> data;

    /** 进度子类型（node_progress 事件有效: thinking / tool_call / tool_result） */
    private String progressType;

    /** 当前迭代次数（graph_iteration 事件有效） */
    private int iterationCount;

    /** 最大迭代次数（graph_iteration 事件有效） */
    private int maxIterations;

    /** 条件边标签（branch_taken 增强字段，如 "passed" / "failed" / "confirmed"） */
    private String condition;

    /** 起始节点（branch_taken 事件 from 字段） */
    private String from;

    /** 目标节点（branch_taken 事件 to 字段） */
    private String to;

    public static StreamChunk thinking(String content, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_THINKING)
                .content(content)
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk toolCall(String toolName, String arguments, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_TOOL_CALL)
                .toolName(toolName)
                .toolArguments(arguments)
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk toolResult(String toolName, String resultSummary, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_TOOL_RESULT)
                .toolName(toolName)
                .toolResult(resultSummary)
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk final_(String conversationId, String answer, long durationMs) {
        return StreamChunk.builder()
                .type(TYPE_FINAL)
                .content(answer)
                .conversationId(conversationId)
                .durationMs(durationMs)
                .build();
    }

    // ========== 全流程流式新增工厂方法 ==========

    /**
     * LLM 思考过程的一个 token（实时推送）
     */
    public static StreamChunk thinkingToken(String token, String conversationId, String iterationInfo) {
        return StreamChunk.builder()
                .type(TYPE_THINKING_TOKEN)
                .content(token)
                .conversationId(conversationId)
                .iterationInfo(iterationInfo)
                .build();
    }

    /**
     * 工具调用开始（前置通知）
     */
    public static StreamChunk toolCallStart(String conversationId, String msg) {
        return StreamChunk.builder()
                .type(TYPE_TOOL_CALL_START)
                .content(msg)
                .conversationId(conversationId)
                .build();
    }

    /**
     * 工具执行出错
     */
    public static StreamChunk toolError(String toolName, String error, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_TOOL_ERROR)
                .toolName(toolName)
                .toolResult(error)
                .conversationId(conversationId)
                .build();
    }

    /**
     * 迭代之间的分隔事件
     */
    public static StreamChunk iterationSeparator(String conversationId, String content) {
        return StreamChunk.builder()
                .type(TYPE_ITERATION_SEPARATOR)
                .content(content)
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk error(String conversationId, String errorMessage, long durationMs) {
        return StreamChunk.builder()
                .type(TYPE_ERROR)
                .content(errorMessage)
                .conversationId(conversationId)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 会话初始化事件 — SSE 流的第一个事件
     *
     * 无论前端是否传 conversationId,后端都会在流的最开始下发一个 session_init,
     * 告诉前端当前会话的 ID。前端拿到后可以写进 URL hash 实现刷新保活。
     *
     * @param conversationId 当前会话的 ID（由后端生成或校验通过后的值）
     */
    public static StreamChunk sessionInit(String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_SESSION_INIT)
                .conversationId(conversationId)
                .build();
    }

    /**
     * 心跳事件 — 反问阻塞期间每 15s 推一次,防止反向代理 idle timeout
     */
    public static StreamChunk heartbeat(String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_HEARTBEAT)
                .conversationId(conversationId)
                .content("heartbeat")
                .build();
    }

    /**
     * 反问事件工厂
     *
     * @param conversationId  会话 ID
     * @param questionId      问题 UUID
     * @param question        问题文本
     * @param optionsJson     预设选项 JSON 数组：[{label,value}, ...]
     * @param allowCustom     是否允许用户自由输入
     */
    public static StreamChunk clarificationRequest(String conversationId, String questionId,
                                                   String question, String optionsJson,
                                                   boolean allowCustom) {
        return StreamChunk.builder()
                .type(TYPE_CLARIFICATION_REQUEST)
                .conversationId(conversationId)
                .questionId(questionId)
                .content(question)
                .toolArguments(optionsJson)
                .allowCustom(allowCustom)
                .build();
    }

    public static StreamChunk timestamp(String content, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_THINKING)
                .content(content)
                .conversationId(conversationId)
                .build();
    }

    // ============ Graph 事件工厂方法（Phase 3 Day 6）============

    /**
     * @deprecated 由 {@link #nodeStatus(String, String, String)} 替代
     */
    @Deprecated
    public static StreamChunk nodeStart(String nodeName, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_NODE_START)
                .toolName(nodeName)
                .content("节点开始")
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk nodeEnd(String nodeName, String conversationId, long durationMs) {
        return StreamChunk.builder()
                .type(TYPE_NODE_END)
                .toolName(nodeName)
                .content("节点完成")
                .conversationId(conversationId)
                .durationMs(durationMs)
                .build();
    }

    public static StreamChunk nodeWarning(String nodeName, String message, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_NODE_WARNING)
                .toolName(nodeName)
                .content(message)
                .conversationId(conversationId)
                .build();
    }

    public static StreamChunk branchTaken(String fromNode, String toNode, String condition, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_BRANCH_TAKEN)
                .toolName(fromNode)
                .toolArguments(toNode)
                .from(fromNode)
                .to(toNode)
                .condition(condition)
                .content("路由: " + fromNode + " -> " + toNode)
                .conversationId(conversationId)
                .build();
    }

    // ============ Graph 流可视化新增工厂方法（Phase 3 Day 7）============

    /**
     * Graph 拓扑定义事件 — 流的第 2 个事件，描述 DAG 结构供前端绘制流程图
     *
     * @param nodes  节点定义列表 [{id, label, type, description}, ...]
     * @param edges  边定义列表 [{from, to, label, conditions}, ...]
     * @param startNode 起始节点 ID
     * @param endNode   终止节点 ID
     */
    public static StreamChunk graphTopology(java.util.List<Map<String, Object>> nodes,
                                             java.util.List<Map<String, Object>> edges,
                                             String startNode, String endNode,
                                             String conversationId) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("nodes", nodes);
        data.put("edges", edges);
        data.put("startNode", startNode);
        data.put("endNode", endNode);
        return StreamChunk.builder()
                .type(TYPE_GRAPH_TOPOLOGY)
                .content("topology")
                .data(data)
                .conversationId(conversationId)
                .build();
    }

    /**
     * 节点状态变化事件
     *
     * @param node     节点 ID
     * @param status   状态: queued / running / completing / done / error / skipped
     */
    public static StreamChunk nodeStatus(String node, String status, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_NODE_STATUS)
                .node(node)
                .nodeStatus(status)
                .conversationId(conversationId)
                .build();
    }

    /**
     * 节点内部进展事件（LLM 思考片段 / 工具调用中间结果）
     *
     * @param node         节点 ID
     * @param progressType 进展类型: thinking / tool_call / tool_result
     * @param content      进展内容
     */
    public static StreamChunk nodeProgress(String node, String progressType, String content, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_NODE_PROGRESS)
                .node(node)
                .progressType(progressType)
                .content(content)
                .conversationId(conversationId)
                .build();
    }

    /**
     * 节点输出数据事件
     *
     * @param node     节点 ID
     * @param dataType 数据类型: constraints / route / dayplans / budget / validation / report / decision
     * @param data     结构化数据
     */
    public static StreamChunk nodeData(String node, String dataType, Map<String, Object> data, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_NODE_DATA)
                .node(node)
                .dataType(dataType)
                .data(data)
                .conversationId(conversationId)
                .build();
    }

    /**
     * 迭代跟踪事件 — Manager 回退时发射
     *
     * @param count 当前迭代次数（从 1 开始）
     * @param max   最大迭代次数
     * @param reason 回退原因
     */
    public static StreamChunk graphIteration(int count, int max, String reason, String conversationId) {
        return StreamChunk.builder()
                .type(TYPE_GRAPH_ITERATION)
                .iterationCount(count)
                .maxIterations(max)
                .content(reason)
                .conversationId(conversationId)
                .build();
    }
}
