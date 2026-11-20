package uno.zhuchen.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("memory_raw_event")
public class MemoryRawEventEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String userId;

    private String conversationId;

    private Long messageId;

    private String eventKey;

    private String content;

    private String candidateJson;

    private String source;

    private String eventStatus;

    private String resultStatus;

    private String resultJson;

    private String errorMessage;

    private Integer attemptCount;

    private LocalDateTime processedAt;

    private LocalDateTime createdAt;
}
