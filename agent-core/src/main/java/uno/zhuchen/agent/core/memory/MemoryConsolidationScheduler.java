package uno.zhuchen.agent.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.memory.consolidation.enabled", havingValue = "true")
public class MemoryConsolidationScheduler {

    private final MemoryAdminService adminService;
    private final int retentionDays;

    public MemoryConsolidationScheduler(MemoryAdminService adminService,
                                        @org.springframework.beans.factory.annotation.Value("${app.memory.retention-days:365}") int retentionDays) {
        this.adminService = adminService;
        this.retentionDays = retentionDays;
    }

    @Scheduled(initialDelayString = "${app.memory.consolidation.initial-delay-ms:60000}",
            fixedDelayString = "${app.memory.consolidation.interval-ms:3600000}")
    public void run() {
        try {
            int processed = adminService.processPendingEvents(20);
            int expired = adminService.expireStaleItems(retentionDays);
            log.info("[memory-consolidation] processed={}, expired={}", processed, expired);
        } catch (Exception e) {
            log.warn("[memory-consolidation] 执行失败: {}", e.getMessage());
        }
    }
}
