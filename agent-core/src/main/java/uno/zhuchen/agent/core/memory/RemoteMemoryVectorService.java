package uno.zhuchen.agent.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import uno.zhuchen.agent.config.McpClientProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆向量服务(远程实现) — 通过 mcp-server 的 {@code /api/memory-vectors} 访问 Milvus。
 *
 * <p>为什么放在 mcp-server 侧执行: Embedding 模型与 Milvus 客户端都在 mcp-server,
 * agent-core 不重复引入向量依赖, 只用 HTTP 转发, 保持"知识库/记忆向量同源"。
 *
 * <p>降级策略(内存检索不能反过来拖垮对话):
 * <ul>
 *   <li>{@link #available()} 带 60 秒 TTL 的状态缓存, 避免每次召回都打一次状态接口</li>
 *   <li>所有远程失败都吞掉并返回空结果, 由 {@link MemoryRetrievalService} 回退结构化排序</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.memory.hybrid.vector-enabled", havingValue = "true")
public class RemoteMemoryVectorService implements MemoryVectorService {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final long STATUS_TTL_MS = 60_000L;

    private final WebClient client;
    private final boolean configured;

    private volatile boolean remoteEnabled;
    private volatile long statusCheckedAt;

    public RemoteMemoryVectorService(McpClientProperties mcpClientProperties,
                                     @Value("${app.memory.hybrid.vector-enabled:false}") boolean configured) {
        this.client = WebClient.builder().baseUrl(mcpClientProperties.getServerUrl()).build();
        this.configured = configured;
    }

    @Override
    public boolean available() {
        if (!configured) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - statusCheckedAt < STATUS_TTL_MS) {
            return remoteEnabled;
        }
        try {
            Map<String, Object> status = client.get()
                    .uri("/api/memory-vectors/status")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block(TIMEOUT);
            remoteEnabled = status != null && Boolean.TRUE.equals(status.get("enabled"));
        } catch (Exception e) {
            remoteEnabled = false;
            log.debug("[memory-vector] 状态探测失败, 本次按不可用处理: {}", e.getMessage());
        }
        statusCheckedAt = now;
        return remoteEnabled;
    }

    @Override
    public void upsert(MemoryItemRecord item) {
        if (item == null || item.id() == null || !available()) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", item.id());
        record.put("tenantId", item.tenantId());
        record.put("userId", item.userId());
        record.put("projectId", item.projectId());
        record.put("memoryType", item.type() == null ? "" : item.type().name());
        record.put("memoryKey", item.key());
        record.put("content", item.content());
        try {
            client.post()
                    .uri("/api/memory-vectors/upsert")
                    .bodyValue(List.of(record))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block(TIMEOUT);
        } catch (Exception e) {
            log.warn("[memory-vector] 写入失败(忽略, 不影响主链路): id={}, error={}", item.id(), e.getMessage());
        }
    }

    @Override
    public void delete(String memoryItemId) {
        if (memoryItemId == null || memoryItemId.isBlank() || !available()) {
            return;
        }
        try {
            client.delete()
                    .uri("/api/memory-vectors/{id}", memoryItemId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block(TIMEOUT);
        } catch (Exception e) {
            log.warn("[memory-vector] 删除失败(忽略): id={}, error={}", memoryItemId, e.getMessage());
        }
    }

    @Override
    public List<MemoryVectorHit> search(String tenantId, String userId, String projectId,
                                        String query, int topK, double minScore) {
        if (!available() || query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("userId", userId);
        body.put("projectId", projectId);
        body.put("query", query);
        body.put("topK", topK);
        body.put("minScore", minScore);
        try {
            List<RemoteHit> hits = client.post()
                    .uri("/api/memory-vectors/search")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<RemoteHit>>() {
                    })
                    .block(TIMEOUT);
            if (hits == null) {
                return List.of();
            }
            return hits.stream()
                    .map(hit -> new MemoryVectorHit(hit.id(), hit.score(), hit.content()))
                    .toList();
        } catch (Exception e) {
            log.warn("[memory-vector] 语义召回失败, 回退结构化排序: error={}", e.getMessage());
            return List.of();
        }
    }

    /** mcp-server 返回体的反序列化载体 */
    private record RemoteHit(String id, double score, String content, String memoryType, String memoryKey) {
    }
}
