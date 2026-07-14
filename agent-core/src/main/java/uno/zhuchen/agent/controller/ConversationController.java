package uno.zhuchen.agent.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.domain.vo.ConversationDetailVO;
import uno.zhuchen.agent.persistence.service.ConversationService;
import uno.zhuchen.agent.domain.vo.ConversationVO;
import uno.zhuchen.agent.common.exception.NotFoundException;
import uno.zhuchen.agent.common.PageResult;

/**
 * 对话管理 REST 端点。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 对话列表（分页，按最近活动排序）。
     */
    @GetMapping
    public Result<PageResult<ConversationVO>> listConversations(
            @RequestParam(defaultValue = "anonymous") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<ConversationVO> result =
                conversationService.listConversations(userId, page, size);
        return Result.success(result);
    }

    /**
     * 对话详情（含消息列表）。
     */
    @GetMapping("/{id}")
    public Result<ConversationDetailVO> getConversation(@PathVariable String id) {
        try {
            ConversationDetailVO detail = conversationService.getConversation(id);
            return Result.success(detail);
        } catch (NotFoundException e) {
            return Result.error(404, e.getMessage());
        }
    }

    /**
     * 更新对话标题。
     */
    @PutMapping("/{id}")
    public Result<Void> updateConversation(@PathVariable String id,
                                            @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return Result.error(400, "title 不能为空");
        }
        conversationService.updateConversation(id, title.trim());
        return Result.success(null);
    }

    /**
     * 软删除对话。
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteConversation(@PathVariable String id) {
        conversationService.deleteConversation(id);
        return Result.success(null);
    }
}
