package uno.zhuchen.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
@TableName("conversation")
public class ConversationEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("user_id")
    private String userId = "anonymous";

    private String title;

    private String mode = "react";

    private String status = "active";

    @TableField("message_count")
    private Integer messageCount = 0;

    private String model;

    @TableField("first_message")
    private String firstMessage;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
