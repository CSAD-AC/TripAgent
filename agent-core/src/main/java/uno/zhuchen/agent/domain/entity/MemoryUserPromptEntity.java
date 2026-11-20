package uno.zhuchen.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("memory_user_prompt")
public class MemoryUserPromptEntity {

    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    private String content;

    private Integer charCount;

    private Integer version;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;
}
