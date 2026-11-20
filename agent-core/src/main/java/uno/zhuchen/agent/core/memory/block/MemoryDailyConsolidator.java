package uno.zhuchen.agent.core.memory.block;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.domain.entity.MessageEntity;
import uno.zhuchen.agent.persistence.query.MessageQuery;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.memory.blocks.enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryDailyConsolidator {

    private final MemoryWatermarkService watermarkService;
    private final MessageQuery messageQuery;
    private final MemoryRawEventService rawEventService;
    private final int minNewMessages;

    public MemoryDailyConsolidator(MemoryWatermarkService watermarkService,
                                   MessageQuery messageQuery,
                                   MemoryRawEventService rawEventService,
                                   @Value("${app.memory.blocks.daily-min-new-messages:5}") int minNewMessages) {
        this.watermarkService = watermarkService;
        this.messageQuery = messageQuery;
        this.rawEventService = rawEventService;
        this.minNewMessages = Math.max(1, minNewMessages);
    }

    @Scheduled(cron = "${app.memory.blocks.daily-cron:0 0 2 * * *}",
            zone = "${app.memory.blocks.daily-timezone:Asia/Shanghai}")
    public void dailyRun() {
        for (String userId : watermarkService.activeUserIds()) {
            try {
                consolidateUser(userId);
            } catch (Exception e) {
                log.warn("[memory-daily] 用户增量整理失败: userId={}, error={}", userId, e.getMessage());
            }
        }
    }

    void consolidateUser(String userId) {
        Long lastId = watermarkService.lastMessageId(userId);
        List<MessageEntity> fresh = messageQuery.newUserMessagesAfter(userId, lastId, 200);
        if (fresh.size() < minNewMessages) {
            watermarkService.touch(userId, lastId, LocalDateTime.now());
            return;
        }
        for (MessageEntity message : fresh) {
            rawEventService.appendDaily(
                    userId,
                    message.getConversationId(),
                    message.getId(),
                    message.getContent());
        }
        watermarkService.touch(userId, fresh.get(fresh.size() - 1).getId(), LocalDateTime.now());
    }
}
