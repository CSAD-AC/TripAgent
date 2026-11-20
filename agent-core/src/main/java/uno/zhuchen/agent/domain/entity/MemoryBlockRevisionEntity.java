package uno.zhuchen.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("memory_block_revision")
public class MemoryBlockRevisionEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String userId;

    private String blockType;

    private String oldContent;

    private String newContent;

    private String changeSource;

    private LocalDateTime createdAt;
}
