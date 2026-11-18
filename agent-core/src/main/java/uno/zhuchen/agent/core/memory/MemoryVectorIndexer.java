package uno.zhuchen.agent.core.memory;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.domain.entity.MemoryItemEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryItemMapper;

import java.util.List;

/**
 * 记忆向量索引器 — 负责把"需要语义召回"的记忆写进向量库, 并回写索引状态。
 *
 * <p>状态机: PENDING(入库待索引) -> INDEXED / FAILED。
 * 失败只记状态与日志, 不让记忆写入主链路失败; 后续可用 {@code /api/memory/admin/rebuild}
 * 按用户重建。
 */
@Slf4j
@Component
public class MemoryVectorIndexer {

    private final MemoryVectorService vectorService;
    private final MemoryItemService memoryItemService;
    private final MemoryItemMapper itemMapper;

    public MemoryVectorIndexer(MemoryVectorService vectorService,
                               MemoryItemService memoryItemService,
                               MemoryItemMapper itemMapper) {
        this.vectorService = vectorService;
        this.memoryItemService = memoryItemService;
        this.itemMapper = itemMapper;
    }

    /** 单条索引: 只处理 PENDING 状态的条目(EPISODIC) */
    public void index(MemoryItemRecord record) {
        if (record == null || record.id() == null || record.vectorStatus() != MemoryVectorStatus.PENDING) {
            return;
        }
        try {
            vectorService.upsert(record);
            mark(record.id(), MemoryVectorStatus.INDEXED);
        } catch (Exception e) {
            mark(record.id(), MemoryVectorStatus.FAILED);
            log.warn("[memory-vector] 索引失败: id={}, error={}", record.id(), e.getMessage());
        }
    }

    /** 按用户重建: 把该用户全部活跃 EPISODIC 记忆重新写入向量库 */
    public int reindexUser(String tenantId, String userId) {
        List<MemoryItemRecord> items = memoryItemService.list(tenantId, userId, null).stream()
                .filter(item -> item.type() == MemoryItemType.EPISODIC)
                .toList();
        int indexed = 0;
        for (MemoryItemRecord item : items) {
            try {
                vectorService.upsert(item);
                mark(item.id(), MemoryVectorStatus.INDEXED);
                indexed++;
            } catch (Exception e) {
                mark(item.id(), MemoryVectorStatus.FAILED);
                log.warn("[memory-vector] 重建失败: id={}, error={}", item.id(), e.getMessage());
            }
        }
        log.info("[memory-vector] 用户记忆重建完成: tenantId={}, userId={}, indexed={}/{}",
                tenantId, userId, indexed, items.size());
        return indexed;
    }

    private void mark(String id, MemoryVectorStatus status) {
        try {
            itemMapper.update(null, new UpdateWrapper<MemoryItemEntity>()
                    .eq("id", id)
                    .set("embedding_status", status.name()));
        } catch (Exception e) {
            log.debug("[memory-vector] 索引状态回写失败: id={}, status={}", id, status);
        }
    }
}
