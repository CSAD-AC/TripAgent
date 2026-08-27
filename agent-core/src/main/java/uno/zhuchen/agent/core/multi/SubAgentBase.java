package uno.zhuchen.agent.core.multi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.llm.ChatModel;
import uno.zhuchen.agent.core.tool.AskUserTool;
import uno.zhuchen.agent.core.tool.ToolRegistry;
import uno.zhuchen.agent.domain.dto.StreamChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 子代理(SubAgent)抽象基类
 *
 * <p>Multi-Agent 模式的核心抽象. 设计要点:
 * <ul>
 *   <li><b>对外</b>: 实现 {@link ToolCallback}, 以 Tool 身份注册进 ManagerAgent 的
 *       ReAct 循环 — 主管只见工具名 + 参数, 不感知内部实现</li>
 *   <li><b>对内</b>: 持有独立 ChatModel + 专属工具集(ownToolNames), 内部跑一个
 *       轻量 ReAct 循环(思考 → 工具 → 观察)</li>
 *   <li><b>方案 C 透明化</b>: 实现 {@link TransparentAgentTool}, 通过 progress 回调
 *       把完整生命周期(thinking_start / thinking_token / tool_call / tool_result /
 *       final / error)实时上抛, 用户可看到子代理"在想什么 / 在调什么 / 得到什么"</li>
 * </ul>
 *
 * <p>上下文传递: ManagerAgent 调用 {@link #callTransparent} 时显式传入
 * conversationId / traceId / modelId / progress 回调, 避免在 ToolCallback 同步入口
 * 上引入 ThreadLocal(askUser 除外, 它仍走 AskUserTool 自身的 ThreadLocal 通道).
 *
 * <p>历史隔离(方案 C 约束): 子代理<strong>不读取主对话历史</strong>, 每次只基于
 * 主管下发的 task 独立推理, 以控制 token 消耗 — 消息序列恒为
 * [SystemMessage(systemPrompt), UserMessage(task)].
 */
public abstract class SubAgentBase implements ToolCallback, TransparentAgentTool {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 子代理名称(即工具名, 注册给主管 LLM) */
    protected final String agentName;

    /** 子代理系统提示词 */
    protected final String systemPrompt;

    /** 模型路由(惰性注入代理, 打破 ToolCallbackResolver 收集链上的循环依赖) */
    private final ModelRouter modelRouter;

    /** MCP/本地工具注册表 — 子代理按名挑选自己的工具子集 */
    protected final ToolRegistry toolRegistry;

    private final ObjectMapper objectMapper;

    /** 内部 ReAct 循环最大轮次 */
    protected final int maxRounds;

    private final ToolDefinition definition;

    /**
     * 注意: 构造器内<strong>不得调用</strong> modelRouter 的任何方法!
     * 子代理实现 ToolCallback, 会被 Spring AI 的 ToolCallingAutoConfiguration 自动收集,
     * 若构造期解析 LLM 会形成 modelRouter → ChatModel → toolCallbackResolver → 子代理 → modelRouter
     * 的循环依赖. 因此 LLM 解析延迟到首次 {@link #callTransparent} (此时 Context 已完整初始化).
     */
    protected SubAgentBase(String agentName, String description, String systemPrompt,
                           ModelRouter modelRouter, ToolRegistry toolRegistry,
                           ObjectMapper objectMapper, int maxRounds) {
        this.agentName = agentName;
        this.systemPrompt = systemPrompt;
        this.modelRouter = modelRouter;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.maxRounds = maxRounds;
        this.definition = ToolDefinition.builder()
                .name(agentName)
                .description(description)
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "task": {
                              "type": "string",
                              "description": "分配给子代理的具体任务描述, 包含任务目标与已知信息"
                            }
                          },
                          "required": ["task"]
                        }
                        """)
                .build();
    }

    /** 子代理声明的可用工具名(从 ToolRegistry 按名筛选, 缺失工具跳过并告警) */
    protected abstract String[] ownToolNames();

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    /** ToolCallback 契约入口(无会话上下文, 供框架直接调用时兜底) */
    @Override
    public String call(String toolInput) {
        return callTransparent(toolInput, null, null, null, null);
    }

    /** 带会话上下文的调用入口(ManagerAgent 使用) */
    public String call(String toolInput, String conversationId, String traceId) {
        return callTransparent(toolInput, conversationId, traceId, null, null);
    }

    /**
     * 带会话上下文 + 模型 + 进度回调的调用入口(方案 C 完整通道)
     *
     * @param modelId 模型业务 ID(透传给子代理内部 LLM; null = 注册表默认)
     * @param progress 进度回调(发射 node_progress 事件到 SSE; 可为 null)
     */
    @Override
    public String callTransparent(String toolInput, String conversationId, String traceId,
                                  String modelId, Consumer<StreamChunk> progress) {
        String task = parseTask(toolInput);
        if (task == null || task.isBlank()) {
            return "错误: 子代理 " + agentName + " 缺少 task 参数";
        }
        // 按 modelId 解析 LLM 实现与 API 模型名(轻量, 每次调用解析, 不做实例缓存)
        ChatModel model = modelRouter.route(modelId);
        String resolvedModelName = modelRouter.resolveModelName(modelId);

        // 历史隔离: 不读主对话历史, 仅基于主管下发的 task
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(task));
        ToolCallback[] tools = ownTools();
        log.info("[traceId={}] 子代理 [{}] 开始执行任务, model={}, 可用工具: {}", traceId, agentName,
                resolvedModelName,
                java.util.Arrays.stream(tools)
                        .map(t -> t.getToolDefinition().name())
                        .toList());
        long start = System.currentTimeMillis();

        for (int round = 0; round < maxRounds; round++) {
            emitProgress(progress, conversationId, "thinking_start",
                    "第 " + (round + 1) + " 轮推理", round + 1);
            AssistantMessage resp;
            try {
                resp = streamAndAccumulate(model, messages, resolvedModelName, tools,
                        conversationId, traceId, round, progress);
            } catch (Exception e) {
                log.warn("[traceId={}] 子代理 [{}] LLM 流式调用异常: {}", traceId, agentName, e.getMessage());
                emitProgress(progress, conversationId, "error",
                        "LLM 调用异常: " + e.getMessage(), round + 1);
                return "子代理 " + agentName + " 执行失败: " + e.getMessage();
            }
            if (resp == null) {
                emitProgress(progress, conversationId, "error", "LLM 无有效输出", round + 1);
                return "子代理 " + agentName + " 没有返回有效结果";
            }

            if (!resp.hasToolCalls()) {
                String text = resp.getText();
                long duration = System.currentTimeMillis() - start;
                log.info("[traceId={}] 子代理 [{}] 完成, 耗时 {}ms, 回答长度={}",
                        traceId, agentName, duration, text != null ? text.length() : 0);
                emitProgress(progress, conversationId, "final",
                        text != null && !text.isBlank() ? truncate(text, 200) : "(子代理返回空回答)",
                        round + 1);
                return text != null && !text.isBlank() ? text : "(子代理返回空回答)";
            }

            // 有工具调用 → 执行并注入观察结果, 进入下一轮
            messages.add(resp);
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : resp.getToolCalls()) {
                String argSummary = truncate(tc.arguments(), 120);
                emitProgress(progress, conversationId, "tool_call",
                        tc.name() + (argSummary.isEmpty() ? "" : " " + argSummary), round + 1);
                String result = executeTool(tc, conversationId, traceId);
                emitProgress(progress, conversationId, "tool_result",
                        truncate(result, 200), round + 1);
                responses.add(new ToolResponseMessage.ToolResponse(
                        tc.id(), tc.name(), result));
            }
            for (ToolResponseMessage.ToolResponse tr : responses) {
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(tr))
                        .build());
            }
        }

        // 达到最大轮次仍未收敛
        String lastText = messages.isEmpty() ? ""
                : messages.get(messages.size() - 1).getText();
        emitProgress(progress, conversationId, "error",
                "在 " + maxRounds + " 轮内未得出最终结论", maxRounds);
        return lastText != null && !lastText.isBlank()
                ? lastText
                : "子代理 " + agentName + " 在 " + maxRounds + " 轮内未得出最终结论";
    }

    /**
     * 流式调用 LLM 并累积本轮结果, 思考 token 逐段实时上抛
     *
     * <p>与 ReactAgent 同款机制: 每个 chunk 的 output 是 AssistantMessage,
     * 文本逐 chunk 上抛(thinking_token), toolCalls 按 id 覆盖累积(跨 chunk 增量),
     * 用 blockLast() 同步等待流结束(子代理跑在 boundedElastic 阻塞线程上).
     */
    private AssistantMessage streamAndAccumulate(ChatModel model, List<Message> messages,
                                                 String modelName, ToolCallback[] tools,
                                                 String conversationId, String traceId,
                                                 int round, Consumer<StreamChunk> progress) {
        RoundAccumulator acc = new RoundAccumulator();
        model.stream(messages, modelName, tools)
                .doOnNext(chunk -> {
                    AssistantMessage msg = chunk.getResult().getOutput();
                    acc.append(msg);
                    String text = msg.getText();
                    if (text != null && !text.isEmpty()) {
                        emitProgress(progress, conversationId, "thinking_token", text, round + 1);
                    }
                })
                .doOnError(e -> log.warn("[traceId={}] 子代理 [{}] 流式错误: {}", traceId, agentName, e.getMessage()))
                .blockLast();
        if (acc.toolCalls().isEmpty()) {
            return AssistantMessage.builder().content(acc.text()).build();
        }
        return AssistantMessage.builder().content(acc.text()).toolCalls(acc.toolCalls()).build();
    }

    /** 单工具执行(同步阻塞, 由 Manager 的 boundedElastic 线程承载) */
    private String executeTool(AssistantMessage.ToolCall tc, String conversationId, String traceId) {
        ToolCallback tool = toolRegistry.getByName(tc.name());
        if (tool == null) {
            log.warn("[traceId={}] 子代理 [{}] 未知工具: {}", traceId, agentName, tc.name());
            return "错误: 未知工具 '" + tc.name() + "'";
        }
        boolean askUser = "askUser".equals(tc.name());
        if (askUser && conversationId == null) {
            return "错误: askUser 需要会话上下文, 当前子代理调用缺少 conversationId";
        }
        try {
            if (askUser) {
                AskUserTool.setContext(conversationId, traceId == null ? "" : traceId);
            }
            long t0 = System.currentTimeMillis();
            String result = tool.call(tc.arguments());
            log.debug("[traceId={}] 子代理 [{}] 工具 {} 完成, 耗时 {}ms",
                    traceId, agentName, tc.name(), System.currentTimeMillis() - t0);
            return result;
        } catch (Exception e) {
            log.warn("[traceId={}] 子代理 [{}] 工具 {} 异常: {}",
                    traceId, agentName, tc.name(), e.getMessage());
            return "工具执行失败: " + e.getMessage();
        } finally {
            if (askUser) {
                AskUserTool.clearContext();
            }
        }
    }

    /** 从 ToolRegistry 按名筛选子代理专属工具 */
    protected ToolCallback[] ownTools() {
        List<ToolCallback> list = new ArrayList<>();
        for (String name : ownToolNames()) {
            ToolCallback t = toolRegistry.getByName(name);
            if (t != null) {
                list.add(t);
            } else {
                log.warn("子代理 [{}] 依赖的工具未注册: {}", agentName, name);
            }
        }
        return list.toArray(new ToolCallback[0]);
    }

    /** 解析 ToolCallback 入参 JSON 中的 task 字段; 非 JSON 时把原文当任务 */
    private String parseTask(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            JsonNode task = node.get("task");
            return task != null ? task.asText() : "";
        } catch (Exception e) {
            return toolInput.trim();
        }
    }

    private void emitProgress(Consumer<StreamChunk> progress, String conversationId,
                              String progressType, String content, Integer subIteration) {
        if (progress != null) {
            progress.accept(StreamChunk.nodeProgress(
                    agentName, progressType, content, subIteration,
                    conversationId == null ? "" : conversationId));
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 流式 chunk 累积器(参考 ReactAgent.RoundAccumulator):
     * 文本 StringBuilder 追加, toolCalls 按 id 后写覆盖(跨 chunk 保留最完整 arguments)
     */
    private static final class RoundAccumulator {
        private final StringBuilder thoughtBuilder = new StringBuilder();
        private final Map<String, AssistantMessage.ToolCall> toolCallMap = new LinkedHashMap<>();

        void append(AssistantMessage chunk) {
            String text = chunk.getText();
            if (text != null && !text.isEmpty()) {
                thoughtBuilder.append(text);
            }
            if (chunk.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : chunk.getToolCalls()) {
                    toolCallMap.put(tc.id(), tc);
                }
            }
        }

        String text() {
            return thoughtBuilder.toString();
        }

        List<AssistantMessage.ToolCall> toolCalls() {
            return List.copyOf(toolCallMap.values());
        }
    }
}
