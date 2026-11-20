package uno.zhuchen.agent.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.domain.entity.MemoryIngestEventEntity;

import java.util.List;

@Slf4j
@Component
public class MemoryExtractionWorker {

    private final MemoryIngestService ingestService;
    private final MemoryCandidateExtractor extractor;
    private final MemoryItemService memoryItemService;
    private final MemoryVectorIndexer vectorIndexer;
    private final ObjectMapper objectMapper;

    public MemoryExtractionWorker(MemoryIngestService ingestService,
                                  MemoryCandidateExtractor extractor,
                                  MemoryItemService memoryItemService,
                                  MemoryVectorIndexer vectorIndexer,
                                  ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.extractor = extractor;
        this.memoryItemService = memoryItemService;
        this.vectorIndexer = vectorIndexer;
        this.objectMapper = objectMapper;
    }

    public int processPendingBatch(int limit) {
        List<MemoryIngestEventEntity> events = ingestService.claimBatch(limit);
        int processed = 0;
        for (MemoryIngestEventEntity event : events) {
            try {
                List<MemoryExtractionResult> candidates = extractor.extract(event.getPayloadJson());
                if (candidates.isEmpty()) {
                    ingestService.markSkipped(event.getId());
                    continue;
                }
                for (MemoryExtractionResult candidate : candidates) {
                    MemoryItemRecord saved = memoryItemService.upsert(
                            event.getTenantId(),
                            event.getUserId(),
                            readProjectId(event.getPayloadJson()),
                            candidate.type(),
                            candidate.key(),
                            candidate.value(),
                            "user_message",
                            event.getConversationId(),
                            null,
                            candidate.confidence(),
                            candidate.importance(),
                            extractor.version());
                    // EPISODIC 需要语义召回, 入库后立刻尝试向量索引(失败只改状态)
                    vectorIndexer.index(saved);
                }
                ingestService.markDone(event.getId());
                processed++;
            } catch (Exception e) {
                log.warn("[memory-worker] 处理事件失败: id={}, error={}", event.getId(), e.getMessage());
                ingestService.markFailed(event.getId(), e.getMessage());
            }
        }
        return processed;
    }

    private String readProjectId(String payloadJson) {
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            return node.hasNonNull("projectId") ? node.get("projectId").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
