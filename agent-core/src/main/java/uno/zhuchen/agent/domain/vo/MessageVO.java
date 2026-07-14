package uno.zhuchen.agent.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uno.zhuchen.agent.domain.entity.MessageEntity;

import java.time.LocalDateTime;

/**
 * 消息 VO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageVO {

    private Long id;
    private String role;
    private String content;
    private String model;
    private Integer tokenCount;
    private String traceId;
    private Integer sequenceNum;
    private String metadata;     // 结构化元数据(JSON)
    private LocalDateTime createdAt;

    public static MessageVO fromEntity(MessageEntity entity) {
        return MessageVO.builder()
                .id(entity.getId())
                .role(entity.getRole())
                .content(entity.getContent())
                .model(entity.getModel())
                .tokenCount(entity.getTokenCount())
                .traceId(entity.getTraceId())
                .sequenceNum(entity.getSequenceNum())
                .metadata(entity.getMetadata())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
