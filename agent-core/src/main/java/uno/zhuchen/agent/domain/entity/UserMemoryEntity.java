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
@TableName("user_memory")
public class UserMemoryEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField("memory_type")
    private String memoryType;

    @TableField("memory_key")
    private String memoryKey;

    @TableField("memory_value")
    private String memoryValue;

    @TableField("source_conversation_id")
    private String sourceConversationId;

    private BigDecimal confidence = new BigDecimal("0.800");

    /**
     * 数据库列名为 status, 但 Java 字段不能叫 status:
     * 项目全局 MyBatis-Plus 逻辑删除字段就是 status, 同名会被自动注入删除条件.
     */
    @TableField("status")
    private String memoryStatus = "active";

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
