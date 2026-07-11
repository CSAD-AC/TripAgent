package uno.zhuchen.agent.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;
import uno.zhuchen.agent.core.agent.ReactAgent;
import uno.zhuchen.agent.core.clarify.ClarificationBroker;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.common.TraceContext;
import uno.zhuchen.agent.domain.dto.ChatDTO;
import uno.zhuchen.agent.domain.dto.ChatRequest;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.domain.vo.ChatVO;
import uno.zhuchen.workflow.builder.GraphStreamRunner;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Agent 聊天控制器
 *
 * 职责：接收外部请求 → 调用 Agent 核心 → DTO 转 VO 返回
 *
 * 支持反问工具的 SSE 推送：
 * - 流式接口注册 ClarificationEmitter，让反问事件能推入 SSE
 * - 新增 POST /api/chat/answer 端点接收用户回答
 *
 * conversationId 生命周期管理（后端权威）：
 * - 新会话：前端不传 ID,后端生成 UUID 并通过 session_init 事件下发
 * - 续聊：前端传 ID(从 URL hash 取),后端校验格式(必须是合法 UUID)
 * - 非法 ID(非 UUID 格式):返回 400
 * - /chat/answer 强制要求 conversationId,用于路由校验
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** 心跳间隔(秒),小于多数反向代理 60s idle timeout */
    private static final int HEARTBEAT_INTERVAL_SECONDS = 15;

    private final ReactAgent reactAgent;
    private final ClarificationBroker clarificationBroker;
    private final GraphStreamRunner graphStreamRunner;

    public AgentController(ReactAgent reactAgent, ClarificationBroker clarificationBroker,
                           GraphStreamRunner graphStreamRunner) {
        this.reactAgent = reactAgent;
        this.clarificationBroker = clarificationBroker;
        this.graphStreamRunner = graphStreamRunner;
    }

    /**
     * 为每个请求生成 traceId,贯穿整个调用链路的日志都会带这个 ID
     * (trace_id 优化 #8,ReAct / Graph 双模式共享)
     *
     * <p>traceId 在 reactive 路径走 Reactor Context, 在 Graph 路径走 state,
     * 不依赖 MDC (org.slf4j.MDC), 完全消除 ThreadLocal 跨线程问题.
     */
    private String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 同步聊天
     */
    @PostMapping("/chat")
    public Mono<Result<ChatVO>> chat(@Valid @RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.getConversationId());
        log.info("收到聊天请求, conversationId={}, message长度={}",
                conversationId,
                request.getMessage() != null ? request.getMessage().length() : 0);

        return Mono.fromCallable(() -> {
            ChatDTO chatDTO = reactAgent.call(request.getMessage(), conversationId);
            ChatVO vo = ChatVO.from(chatDTO);

            log.info("聊天完成, status={}, durationMs={}",
                    chatDTO.getErrorMessage() != null ? "ERROR"
                            : chatDTO.isMaxIterationsReached() ? "MAX_ITERATIONS" : "SUCCESS",
                    chatDTO.getDurationMs());

            if (chatDTO.getErrorMessage() != null) {
                return Result.error(500, chatDTO.getErrorMessage());
            }
            return Result.success(vo);
        });
    }

    /**
     * 流式聊天（SSE）
     *
     * 事件序列：
     *   event: session_init                    ← 总是第一个事件
     *   data: {"type":"session_init","conversationId":"f8e7-..."}
     *
     *   event: thinking
     *   data: {"type":"thinking","content":"你好","conversationId":"f8e7-..."}
     *
     *   event: clarification_request
     *   data: {"type":"clarification_request","questionId":"uuid","content":"预算?",
     *          "toolArguments":"[{label,value},...]","allowCustom":true,"conversationId":"f8e7-..."}
     *
     *   event: heartbeat                       ← 反问阻塞期间每 15s 推一次
     *   data: {"type":"heartbeat","conversationId":"f8e7-..."}
     *
     *   event: final
     *   data: {"type":"final","content":"...","conversationId":"f8e7-...","durationMs":1234}
     *
     * 关键设计：
     * - session_init 是流的第一个事件,前端可从 conversationId 字段拿到后写进 URL hash
     * - Sinks.Many 收集反问事件;emitter 按 conversationId 注册到 Broker
     * - 心跳流(Flux.interval)在主流未结束时持续推送,防止反向代理 timeout
     * - 所有源(主/旁路/心跳)通过 Flux.merge 并行推送
     * - traceId 链路追踪:
     *   - Reactive 路径: contextWrite 注入 Reactor Context, 下游业务层 deferContextual 读取
     *   - Reactive 路径入口 log: 显式拼 traceId 到消息中
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamChunk> stream(@Valid @RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.getConversationId());
        String traceId = newTraceId();
        long startMs = System.currentTimeMillis();

        // 1. 反问事件旁路 sink
        Sinks.Many<StreamChunk> clarificationSink = Sinks.many().unicast().onBackpressureBuffer();
        Consumer<StreamChunk> emitter = clarificationSink::tryEmitNext;
        try {
            clarificationBroker.registerEmitter(conversationId, emitter);
        } catch (ClarificationBroker.DuplicateSseConnectionException e) {
            // 同一 conversationId 已有活跃 SSE 连接, 拒绝新连接
            log.warn("[traceId={}] SSE 连接被拒绝: {}", traceId, e.getMessage());
            return Flux.just(
                    StreamChunk.sessionInit(conversationId, traceId),
                    StreamChunk.error(conversationId, e.getMessage(),
                            System.currentTimeMillis() - startMs)
            )
            // 提前返回路径也要 contextWrite, 与主流保持 Context 一致性
            .contextWrite(Context.of(TraceContext.KEY, traceId));
        }

        // 2. cleanup guard: doOnTerminate 与 doOnCancel 都可能触发, 用 CAS 保证只清理一次
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable doCleanup = () -> {
            if (cleaned.compareAndSet(false, true)) {
                clarificationBroker.unregisterEmitter(conversationId, emitter);
                clarificationSink.tryEmitComplete();
            }
        };

        // 3. 主流完成信号（避免心跳用 mainStream.last() 二次订阅冷 Flux，导致双 ReAct 循环并行）
        Sinks.One<Void> completionSignal = Sinks.one();

        // 4. 主事件流(thinking_token / tool_call / tool_result / final)
        Flux<StreamChunk> mainStream = reactAgent.stream(request.getMessage(), conversationId)
                .doFinally(signalType -> completionSignal.tryEmitEmpty())
                .doOnTerminate(doCleanup)
                .doOnCancel(() -> {
                    log.info("[traceId={}] SSE 客户端断开, conversationId={}", traceId, conversationId);
                    doCleanup.run();
                });

        // 5. 第一个事件:session_init,携带 traceId; 入口 log 改在 reactive 链上用 deferContextual
        int messageLen = request.getMessage() != null ? request.getMessage().length() : 0;
        Flux<StreamChunk> entryLog = TraceContext.currentTraceIdMono()
                .doOnNext(t -> log.info("[traceId={}] 收到流式聊天请求, conversationId={}, message长度={}",
                        t, conversationId, messageLen))
                .thenMany(Flux.just(StreamChunk.sessionInit(conversationId, traceId)));

        // 6. 心跳流(主事件流未结束时持续推送,主事件流结束则停止)
        //    使用 Sinks.One 而非 mainStream.last()，避免冷 Flux 二次订阅
        Flux<StreamChunk> heartbeat = Flux.interval(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS))
                .map(tick -> StreamChunk.heartbeat(conversationId))
                .takeUntilOther(completionSignal.asMono());

        return Flux.merge(entryLog, mainStream, clarificationSink.asFlux(), heartbeat)
                // traceId 沿 reactive 链传播: Context 免费跨线程, 业务层 deferContextual 读取
                .contextWrite(Context.of(TraceContext.KEY, traceId));
    }

    /**
     * 提交用户对反问的回答
     *
     * 前端弹出问题卡 → 用户点选/输入 → 调用此端点喂入 broker → 阻塞中的 AskUserTool 解除阻塞
     *
     * 要求同时传 conversationId 和 questionId,后端校验两者匹配,防止跨会话误路由
     */
    @PostMapping("/chat/answer")
    public Mono<Result<Void>> submitAnswer(@Valid @RequestBody AnswerRequest request) {
        log.info("收到用户回答: conversationId={}, questionId={}, answer={}",
                request.conversationId(), request.questionId(), request.answer());
        clarificationBroker.submit(request.conversationId(), request.questionId(), request.answer());
        return Mono.just(Result.success(null));
    }

    /**
     * Phase 3 Graph 工作流入口
     *
     * 直接走 Supervisor-Worker-Validator 三件套,不走 ReAct。
     * 适用于旅游规划类请求（含个性化软约束）。
     *
     * 事件序列（由 GraphStreamRunner 内部发射）:
     *   session_init → graph_topology
     *     → node_status(manager, running)
     *     → (node_status(node, done) → branch_taken → node_status(node, running))ⁿ
     *     → (graph_iteration)? (回退时)
     *     → final
     *
     * <p>GraphStreamRunner 已负责 session_init + graph_topology，
     * 本方法只需合并反问旁路流。
     *
     * <p>反问机制：与 /api/chat/stream 端点相同，注册 emitter 到 ClarificationBroker，
     * 让 AskUserTool 在 Graph 跑过程中能阻塞等用户回答。
     *
     * <p>traceId 链路追踪: 与 /chat/stream 一致, 通过 contextWrite 注入 Reactor Context
     * (Graph 路径同时由 GraphStreamRunner 写入 state[INPUT_TRACE_ID] 供 BaseAgent 读取).
     */
    @PostMapping(value = "/chat/graph", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamChunk> graphStream(@Valid @RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.getConversationId());
        String traceId = newTraceId();
        long startMs = System.currentTimeMillis();

        // 1. 反问事件旁路 sink（与 /chat/stream 完全相同的模式）
        Sinks.Many<StreamChunk> clarificationSink = Sinks.many().unicast().onBackpressureBuffer();
        Consumer<StreamChunk> emitter = clarificationSink::tryEmitNext;
        try {
            clarificationBroker.registerEmitter(conversationId, emitter);
        } catch (ClarificationBroker.DuplicateSseConnectionException e) {
            // 同一 conversationId 已有活跃 SSE 连接, 拒绝新连接 (P2 修复)
            log.warn("[traceId={}] Graph SSE 连接被拒绝: {}", traceId, e.getMessage());
            return Flux.just(
                    StreamChunk.sessionInit(conversationId, traceId),
                    StreamChunk.error(conversationId, e.getMessage(),
                            System.currentTimeMillis() - startMs)
            )
            // 提前返回路径也要 contextWrite, 与主流保持 Context 一致性
            .contextWrite(Context.of(TraceContext.KEY, traceId));
        }

        // 2. cleanup guard（与 /chat/stream 同样模式）
        AtomicBoolean graphCleaned = new AtomicBoolean(false);
        Runnable graphCleanup = () -> {
            if (graphCleaned.compareAndSet(false, true)) {
                clarificationBroker.unregisterEmitter(conversationId, emitter);
                clarificationSink.tryEmitComplete();
            }
        };

        // 3. 主事件流（GraphStreamRunner 内部已发射 session_init + graph_topology + 全生命周期事件）
        //    注意: Sinks.Many.unicast() 只允许单订阅者，所以心跳不能用 mainStream.last() 做终止信号
        Sinks.One<Void> graphCompletionSignal = Sinks.one();
        Flux<StreamChunk> mainStream = graphStreamRunner.runStream(request.getMessage(), conversationId, traceId)
                .doFinally(signalType -> graphCompletionSignal.tryEmitEmpty())
                .doOnTerminate(graphCleanup)
                .doOnCancel(() -> {
                    log.info("[traceId={}] SSE 客户端断开, conversationId={}", traceId, conversationId);
                    graphCleanup.run();
                });

        // 4. 入口 log 改在 reactive 链上用 deferContextual (同步块拿不到 ContextView)
        int messageLen = request.getMessage() != null ? request.getMessage().length() : 0;
        Flux<StreamChunk> entryLog = TraceContext.currentTraceIdMono()
                .doOnNext(t -> log.info("[traceId={}] 收到 Graph 流式请求, conversationId={}, message长度={}",
                        t, conversationId, messageLen))
                .thenMany(Flux.<StreamChunk>empty());

        // 5. 心跳流（反问阻塞期间防止反向代理 timeout，用独立的 completionSignal 终止）
        Flux<StreamChunk> heartbeat = Flux.interval(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS))
                .map(tick -> StreamChunk.heartbeat(conversationId))
                .takeUntilOther(graphCompletionSignal.asMono());

        // 6. 合并主事件 + 入口 log + 反问旁路 + 心跳（各自是独立的 sink，互不冲突）
        return Flux.merge(mainStream, entryLog, clarificationSink.asFlux(), heartbeat)
                .contextWrite(Context.of(TraceContext.KEY, traceId));
    }

    /**
     * 解析并校验 conversationId:
     * 规则：
     * - null / 空 → 新会话,生成 UUID
     * - 非空但格式非法 → 抛 IllegalArgumentException(由全局异常处理转 400)
     * - 合法 UUID → 原样返回
     */
    private String resolveConversationId(String input) {
        if (input == null || input.isBlank()) {
            String generated = UUID.randomUUID().toString();
            log.debug("新会话,后端生成 conversationId={}", generated);
            return generated;
        }
        try {
            // 验证 UUID 格式(包含连字符的标准 36 位格式)
            return UUID.fromString(input).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法的 conversationId 格式,必须是 36 位 UUID 字符串: " + input);
        }
    }

    /**
     * 用户回答请求体
     * conversationId 和 questionId 都必填,用于路由校验
     */
    public record AnswerRequest(String conversationId, String questionId, String answer) {}
}
