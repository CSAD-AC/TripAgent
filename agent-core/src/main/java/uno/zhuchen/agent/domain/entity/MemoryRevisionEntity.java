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
@TableName("memory_revision")
public class MemoryRevisionEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("memory_item_id")
    private String memoryItemId;

    @TableField("tenant_id")
    private String tenantId = "default";

    @TableField("user_id")
    private String userId;

    @TableField("operation")
    private String operation;

    @TableField("previous_value")
    private String previousValue;

    @TableField("new_value")
    private String newValue;

    @TableField("actor")
    private String actor;

    @TableField("reason")
    private String reason;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
