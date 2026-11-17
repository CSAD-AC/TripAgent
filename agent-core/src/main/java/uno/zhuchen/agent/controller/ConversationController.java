package uno.zhuchen.agent.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import uno.zhuchen.agent.common.Result;
import uno.zhuchen.agent.domain.dto.UpdateConversationTitleRequest;
import uno.zhuchen.agent.domain.vo.ConversationDetailVO;
import uno.zhuchen.agent.persistence.service.ConversationService;
import uno.zhuchen.agent.domain.vo.ConversationVO;
import uno.zhuchen.agent.common.PageResult;
import uno.zhuchen.agent.security.UserPrincipal;

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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal user) {
        PageResult<ConversationVO> result =
                conversationService.listConversations(user.userId(), page, size);
        return Result.success(result);
    }

    /**
     * 对话详情（含消息列表）。
     */
    @GetMapping("/{id}")
    public Result<ConversationDetailVO> getConversation(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal user) {
        ConversationDetailVO detail = conversationService.getConversation(user.userId(), id);
        return Result.success(detail);
    }

    /**
     * 更新对话标题。
     */
    @PutMapping("/{id}")
    public Result<Void> updateConversation(@PathVariable String id,
                                            @Valid @RequestBody UpdateConversationTitleRequest req,
                                            @AuthenticationPrincipal UserPrincipal user) {
        conversationService.updateConversation(user.userId(), id, req.getTitle().trim());
        return Result.success(null);
    }

    /**
     * 软删除对话。
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteConversation(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal user) {
        conversationService.deleteConversation(user.userId(), id);
        return Result.success(null);
    }
}
