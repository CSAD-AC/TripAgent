package uno.zhuchen.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("memory_ingest_event")
public class MemoryIngestEventEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("tenant_id")
    private String tenantId = "default";

    @TableField("user_id")
    private String userId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("event_type")
    private String eventType;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("event_status")
    private String eventStatus = "pending";

    @TableField("attempt_count")
    private Integer attemptCount = 0;

    @TableField("last_error")
    private String lastError;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("processed_at")
    private LocalDateTime processedAt;
}
