package uno.zhuchen.agent.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 记忆事件消费worker — 定时把 {@code memory_ingest_event} 里 pending 的事件抽成记忆。
 *
 * <p>为什么需要它: 聊天完成后触发服务只负责"入队", 队列不消费的话记忆永远不会落库。
 * 之前只有 {@link MemoryConsolidationScheduler}(默认关闭、1 小时一次)会顺带处理,
 * 等价于异步链路默认不工作。本 worker 与触发开关同生命周期
 * ({@code app.memory.ingest-trigger.enabled}, 默认开启), 5 秒一轮, 让异步真的异步。
 *
 * <p>成本控制: 没有 pending 事件时只做一次 count-free 的 claim(空批即返回),
 * 不产生模型调用; 单轮最多处理 {@code batch-size} 条。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.memory.ingest-trigger.enabled", havingValue = "true", matchIfMissing = true)
public class MemoryIngestWorker {

    private final MemoryAdminService adminService;
    private final int batchSize;

    public MemoryIngestWorker(MemoryAdminService adminService,
                              @Value("${app.memory.ingest-trigger.batch-size:20}") int batchSize) {
        this.adminService = adminService;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(initialDelayString = "${app.memory.ingest-trigger.initial-delay-ms:15000}",
            fixedDelayString = "${app.memory.ingest-trigger.process-interval-ms:5000}")
    public void run() {
        try {
            int processed = adminService.processPendingEvents(batchSize);
            if (processed > 0) {
                log.info("[memory-ingest] 消费完成: processed={}", processed);
            }
        } catch (Exception e) {
            log.warn("[memory-ingest] 消费失败: {}", e.getMessage());
        }
    }
}
