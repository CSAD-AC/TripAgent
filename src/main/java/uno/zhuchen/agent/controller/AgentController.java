package uno.zhuchen.agent.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.zhuchen.agent.agent.ReactAgent;
import uno.zhuchen.agent.domain.dto.ChatDTO;
import uno.zhuchen.agent.domain.dto.ChatRequest;
import uno.zhuchen.agent.domain.vo.ChatVO;

/**
 * Agent 聊天控制器
 *
 * <p>职责：接收外部请求 → 调用 Agent 核心 → DTO 转 VO 返回</p>
 *
 * <pre>
 * 数据流:
 *   ChatRequest (DTO, 入参) → ReactAgent → ChatDTO (内部流转) → ChatVO (展示)
 * </pre>
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
     *
     * @param request 聊天请求（含消息内容和会话ID）
     * @return VO 格式的响应
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatVO> chat(@Valid @RequestBody ChatRequest request) {
        log.info("收到聊天请求, conversationId={}, message长度={}",
                request.getConversationId(),
                request.getMessage() != null ? request.getMessage().length() : 0);

        // 1. 调用 Agent 核心 → 得到内部 DTO
        ChatDTO chatDTO = reactAgent.call(request.getMessage(), request.getConversationId());

        // 2. DTO → VO（前端展示）
        ChatVO vo = ChatVO.from(chatDTO);

        log.info("聊天完成, status={}, durationMs={}",
                chatDTO.getErrorMessage() != null ? "ERROR"
                        : chatDTO.isMaxIterationsReached() ? "MAX_ITERATIONS" : "SUCCESS",
                chatDTO.getDurationMs());
        return ResponseEntity.ok(vo);
    }
}
