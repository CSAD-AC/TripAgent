package uno.zhuchen.agent.core.memory.block;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.memory.blocks.enabled",
        havingValue = "true", matchIfMissing = true)
public class MemoryBlockWorker {

    private final MemoryBlockService service;
    private final int batchSize;

    public MemoryBlockWorker(MemoryBlockService service,
                             @Value("${app.memory.blocks.batch-size:50}") int batchSize) {
        this.service = service;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${app.memory.blocks.consolidate-interval-ms:10000}")
    public void run() {
        try {
            service.processPending(batchSize);
        } catch (Exception e) {
            log.warn("[memory-block] 消费失败: {}", e.getMessage());
        }
    }
}
