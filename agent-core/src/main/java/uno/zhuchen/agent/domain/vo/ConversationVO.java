package uno.zhuchen.agent.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uno.zhuchen.agent.domain.entity.ConversationEntity;

import java.time.LocalDateTime;

/**
 * 对话列表 VO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationVO {

    private String id;
    private String title;
    private String mode;
    private int messageCount;
    private String firstMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ConversationVO fromEntity(ConversationEntity entity) {
        return ConversationVO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .mode(entity.getMode())
                .messageCount(entity.getMessageCount() != null ? entity.getMessageCount() : 0)
                .firstMessage(entity.getFirstMessage())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
