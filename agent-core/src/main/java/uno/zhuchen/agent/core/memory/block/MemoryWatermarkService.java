package uno.zhuchen.agent.core.memory.block;

import org.springframework.stereotype.Service;
import uno.zhuchen.agent.domain.entity.MemoryWatermarkEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryWatermarkMapper;
import uno.zhuchen.agent.persistence.query.MessageQuery;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MemoryWatermarkService {

    private final MemoryWatermarkMapper mapper;
    private final MessageQuery messageQuery;

    public MemoryWatermarkService(MemoryWatermarkMapper mapper, MessageQuery messageQuery) {
        this.mapper = mapper;
        this.messageQuery = messageQuery;
    }

    public List<String> activeUserIds() {
        return messageQuery.activeUserIds();
    }

    public Long lastMessageId(String userId) {
        MemoryWatermarkEntity entity = mapper.selectById(userId);
        return entity == null || entity.getLastMessageId() == null ? 0L : entity.getLastMessageId();
    }

    public void touch(String userId, Long lastMessageId, LocalDateTime runAt) {
        LocalDateTime now = runAt == null ? LocalDateTime.now() : runAt;
        MemoryWatermarkEntity entity = mapper.selectById(userId);
        if (entity == null) {
            entity = new MemoryWatermarkEntity();
            entity.setUserId(userId);
            entity.setLastMessageId(lastMessageId);
            entity.setLastRunAt(now);
            mapper.insert(entity);
            return;
        }
        entity.setLastMessageId(lastMessageId);
        entity.setLastRunAt(now);
        mapper.updateById(entity);
    }
}
