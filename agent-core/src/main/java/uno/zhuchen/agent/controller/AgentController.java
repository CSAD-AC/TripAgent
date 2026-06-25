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
import uno.zhuchen.agent.agent.ReactAgent;
import uno.zhuchen.agent.clarify.ClarificationBroker;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.domain.dto.ChatDTO;
import uno.zhuchen.agent.domain.dto.ChatRequest;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.domain.vo.ChatVO;

import java.time.Duration;
import java.util.UUID;

/**
 * Agent 聊天控制器
 *
 * 职责：接收外部请求 → 调用 Agent 核心 → DTO 转 VO 返回
 *
 * <p>支持反问工具的 SSE 推送：
 * <ul>
 *   <li>流式接口注册 ClarificationEmitter，让反问事件能推入 SSE</li>
 *   <li>新增 POST /api/chat/answer 端点接收用户回答</li>
 * </ul>
 *
 * <p>conversationId 生命周期管理（后端权威）：
 * <ul>
 *   <li>新会话：前端不传 ID,后端生成 UUID 并通过 session_init 事件下发</li>
 *   <li>续聊：前端传 ID(从 URL hash 取),后端校验格式(必须是合法 UUID)</li>
 *   <li>非法 ID(非 UUID 格式):返回 400</li>
 *   <li>/chat/answer 强制要求 conversationId,用于路由校验</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** 心跳间隔(秒),小于多数反向代理 60s idle timeout */
    private static final int HEARTBEAT_INTERVAL_SECONDS = 15;

    private final ReactAgent reactAgent;
    private final ClarificationBroker clarificationBroker;

    public AgentController(ReactAgent reactAgent, ClarificationBroker clarificationBroker) {
        this.reactAgent = reactAgent;
        this.clarificationBroker = clarificationBroker;
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
     * <p>关键设计：
     * <ul>
     *   <li>session_init 是流的第一个事件,前端可从 conversationId 字段拿到后写进 URL hash</li>
     *   <li>Sinks.Many 收集反问事件;emitter 按 conversationId 注册到 Broker</li>
     *   <li>心跳流(Flux.interval)在主流未结束时持续推送,防止反向代理 timeout</li>
     *   <li>所有源(主/旁路/心跳)通过 Flux.merge 并行推送</li>
     * </ul>
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamChunk> stream(@Valid @RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.getConversationId());
        log.info("收到流式聊天请求, conversationId={}, message长度={}",
                conversationId,
                request.getMessage() != null ? request.getMessage().length() : 0);

        // 1. 反问事件旁路 sink
        Sinks.Many<StreamChunk> clarificationSink = Sinks.many().unicast().onBackpressureBuffer();
        clarificationBroker.registerEmitter(conversationId, clarificationSink::tryEmitNext);

        // 2. 主事件流(thinking_token / tool_call / tool_result / final)
        Flux<StreamChunk> mainStream = reactAgent.stream(request.getMessage(), conversationId)
                .doOnTerminate(() -> {
                    clarificationBroker.unregisterEmitter(conversationId);
                    clarificationSink.tryEmitComplete();
                })
                .doOnCancel(() -> {
                    log.info("SSE 客户端断开, conversationId={}", conversationId);
                    clarificationBroker.unregisterEmitter(conversationId);
                    clarificationSink.tryEmitComplete();
                });

        // 3. 第一个事件:session_init
        Flux<StreamChunk> sessionInitEvent = Flux.just(StreamChunk.sessionInit(conversationId));

        // 4. 心跳流(主事件流未结束时持续推送,主事件流结束则停止)
        Flux<StreamChunk> heartbeat = Flux.interval(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS))
                .map(tick -> StreamChunk.heartbeat(conversationId))
                .takeUntilOther(mainStream.last().flux());

        return Flux.merge(sessionInitEvent, mainStream, clarificationSink.asFlux(), heartbeat);
    }

    /**
     * 提交用户对反问的回答
     *
     * <p>前端弹出问题卡 → 用户点选/输入 → 调用此端点喂入 broker → 阻塞中的 AskUserTool 解除阻塞
     *
     * <p>要求同时传 conversationId 和 questionId,后端校验两者匹配,防止跨会话误路由
     */
    @PostMapping("/chat/answer")
    public Mono<Result<Void>> submitAnswer(@Valid @RequestBody AnswerRequest request) {
        log.info("收到用户回答: conversationId={}, questionId={}, answer={}",
                request.conversationId(), request.questionId(), request.answer());
        clarificationBroker.submit(request.conversationId(), request.questionId(), request.answer());
        return Mono.just(Result.success(null));
    }

    /**
     * 解析并校验 conversationId:
     * <ul>
     *   <li>null / 空 → 新会话,生成 UUID</li>
     *   <li>非空但格式非法 → 抛 IllegalArgumentException(由全局异常处理转 400)</li>
     *   <li>合法 UUID → 原样返回</li>
     * </ul>
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
     * <p>conversationId 和 questionId 都必填,用于路由校验
     */
    public record AnswerRequest(String conversationId, String questionId, String answer) {}
}
