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
import uno.zhuchen.agent.agent.ReactAgent;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.domain.dto.ChatDTO;
import uno.zhuchen.agent.domain.dto.ChatRequest;
import uno.zhuchen.agent.domain.dto.StreamChunk;
import uno.zhuchen.agent.domain.vo.ChatVO;

/**
 * Agent 聊天控制器
 *
 * 职责：接收外部请求 → 调用 Agent 核心 → DTO 转 VO 返回
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ReactAgent reactAgent;

    public AgentController(ReactAgent reactAgent) {
        this.reactAgent = reactAgent;
    }

    /**
     * 同步聊天
     */
    @PostMapping("/chat")
    public Mono<Result<ChatVO>> chat(@Valid @RequestBody ChatRequest request) {
        log.info("收到聊天请求, conversationId={}, message长度={}",
                request.getConversationId(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        return Mono.fromCallable(() -> {
            ChatDTO chatDTO = reactAgent.call(request.getMessage(), request.getConversationId());
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
     *   event: thinking
     *   data: {"type":"thinking","content":"你好","conversationId":"abc"}
     *
     *   event: final
     *   data: {"type":"final","content":"你好，我是旅游助手","conversationId":"abc","durationMs":1234}
     *
     * @param request 聊天请求（含消息内容和会话ID）
     * @return SSE 流
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamChunk> stream(@Valid @RequestBody ChatRequest request) {
        log.info("收到流式聊天请求, conversationId={}, message长度={}",
                request.getConversationId(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        return reactAgent.stream(request.getMessage(), request.getConversationId());
    }
}
