package uno.zhuchen.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import uno.zhuchen.agent.domain.entity.MemoryRawEventEntity;

@Mapper
public interface MemoryRawEventMapper extends BaseMapper<MemoryRawEventEntity> {

    @Insert("""
            INSERT IGNORE INTO memory_raw_event
            (id, user_id, conversation_id, message_id, event_key, content, source,
             event_status, attempt_count, processed_at, created_at)
            VALUES
            (#{id}, #{userId}, #{conversationId}, #{messageId}, #{eventKey}, #{content}, #{source},
             #{eventStatus}, #{attemptCount}, #{processedAt}, #{createdAt})
            """)
    int insertIgnore(MemoryRawEventEntity entity);
}
