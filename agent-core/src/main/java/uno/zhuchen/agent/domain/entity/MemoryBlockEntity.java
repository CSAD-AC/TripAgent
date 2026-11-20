package uno.zhuchen.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("memory_block")
public class MemoryBlockEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String userId;

    private String blockType;

    private String content;

    private Integer charCount;

    private Integer version;

    private LocalDateTime generatedEditedAt;

    private String lastChangeSource;

    private LocalDateTime lastChangedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
