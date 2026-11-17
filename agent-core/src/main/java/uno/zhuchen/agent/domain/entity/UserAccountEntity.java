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
@TableName("user_account")
public class UserAccountEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String username;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("display_name")
    private String displayName;

    private String status = "active";

    @TableField("failed_login_count")
    private Integer failedLoginCount = 0;

    @TableField("locked_until")
    private LocalDateTime lockedUntil;

    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
