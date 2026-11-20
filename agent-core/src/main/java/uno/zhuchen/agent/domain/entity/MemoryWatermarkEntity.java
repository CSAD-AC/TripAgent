package uno.zhuchen.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("memory_consolidation_watermark")
public class MemoryWatermarkEntity {

    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    private Long lastMessageId;

    private LocalDateTime lastRunAt;
}
