package uno.zhuchen.agent.persistence.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.zhuchen.agent.common.PageResult;
import uno.zhuchen.agent.common.exception.NotFoundException;
import uno.zhuchen.agent.domain.entity.ConversationEntity;
import uno.zhuchen.agent.domain.entity.MessageEntity;
import uno.zhuchen.agent.domain.vo.ConversationDetailVO;
import uno.zhuchen.agent.domain.vo.ConversationVO;
import uno.zhuchen.agent.domain.vo.MessageVO;
import uno.zhuchen.agent.persistence.mapper.ConversationMapper;
import uno.zhuchen.agent.persistence.mapper.MessageMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话管理业务逻辑。
 */
@Slf4j
@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationService(ConversationMapper conversationMapper,
                                MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * 获取用户对话列表（分页，仅 active）。
     */
    public PageResult<ConversationVO> listConversations(String userId, int pageNum, int size) {
        Page<ConversationEntity> page = new Page<>(pageNum, size);
        Page<ConversationEntity> result = (Page<ConversationEntity>)
                conversationMapper.listByUserId(userId, page);

        List<ConversationVO> list = result.getRecords().stream()
                .map(ConversationVO::fromEntity)
                .collect(Collectors.toList());

        return PageResult.of(list, (int) result.getTotal(), pageNum, size);
    }

    /**
     * 获取单条对话详情（含消息列表）。
     */
    public ConversationDetailVO getConversation(String conversationId) {
        ConversationEntity entity = conversationMapper.selectById(conversationId);
        if (entity == null || "deleted".equals(entity.getStatus())) {
            throw new NotFoundException("对话不存在");
        }
        List<MessageEntity> messages = messageMapper.listByConversation(conversationId);
        List<MessageVO> messageVOs = messages.stream()
                .map(MessageVO::fromEntity)
                .collect(Collectors.toList());
        return ConversationDetailVO.from(entity, messageVOs);
    }

    /**
     * 更新对话标题。
     */
    public void updateConversation(String conversationId, String title) {
        ConversationEntity entity = conversationMapper.selectById(conversationId);
        if (entity == null || "deleted".equals(entity.getStatus())) {
            throw new NotFoundException("对话不存在");
        }
        conversationMapper.updateTitle(conversationId, title);
        log.info("Updated conversation title: {} -> {}", conversationId, title);
    }

    /**
     * 软删除对话及关联消息。
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        // 物理删除消息
        messageMapper.delete(new QueryWrapper<MessageEntity>()
                .eq("conversation_id", conversationId));
        // 软删除对话
        conversationMapper.softDeleteById(conversationId);
        log.info("Deleted conversation: {}", conversationId);
    }
}
