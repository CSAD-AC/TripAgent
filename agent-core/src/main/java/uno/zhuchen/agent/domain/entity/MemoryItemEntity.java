package uno.zhuchen.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("memory_item")
public class MemoryItemEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("tenant_id")
    private String tenantId = "default";

    @TableField("user_id")
    private String userId;

    @TableField("project_id")
    private String projectId;

    @TableField("memory_type")
    private String memoryType;

    @TableField("memory_key")
    private String memoryKey;

    @TableField("memory_value")
    private String memoryValue;

    @TableField("content")
    private String content;

    @TableField("attributes_json")
    private String attributesJson;

    @TableField("source_type")
    private String sourceType;

    @TableField("source_conversation_id")
    private String sourceConversationId;

    @TableField("source_message_id")
    private String sourceMessageId;

    @TableField("confidence")
    private BigDecimal confidence = new BigDecimal("0.800");

    @TableField("importance")
    private BigDecimal importance = new BigDecimal("0.500");

    /** 数据库列是 memory_status，避免与 MyBatis-Plus 全局逻辑删除字段 status 冲突。 */
    @TableField("memory_status")
    private String memoryStatus = "active";

    @TableField("superseded_by")
    private String supersededBy;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("embedding_status")
    private String embeddingStatus = "not_required";

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("embedding_version")
    private String embeddingVersion;

    @TableField("extractor_version")
    private String extractorVersion = "rule-v1";

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("deleted_at")
    private LocalDateTime deletedAt;
}
