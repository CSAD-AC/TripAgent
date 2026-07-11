package uno.zhuchen.agent.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uno.zhuchen.agent.domain.entity.ConversationEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话详情 VO（含消息列表）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationDetailVO {

    private String id;
    private String title;
    private String mode;
    private String status;
    private int messageCount;
    private String model;
    private String firstMessage;
    private List<MessageVO> messages;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ConversationDetailVO from(ConversationEntity entity, List<MessageVO> messages) {
        return ConversationDetailVO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .mode(entity.getMode())
                .status(entity.getStatus())
                .messageCount(entity.getMessageCount() != null ? entity.getMessageCount() : 0)
                .model(entity.getModel())
                .firstMessage(entity.getFirstMessage())
                .messages(messages)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
