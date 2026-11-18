package uno.zhuchen.agent.core.memory;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 记忆向量服务(远程实现) — 通过 mcp-server 的 {@code /api/memory-vectors} 访问 Milvus。
 *
 * <p>为什么放在 mcp-server 侧执行: Embedding 模型与 Milvus 客户端都在 mcp-server,
 * agent-core 不重复引入向量依赖, 只用 HTTP 转发, 保持"知识库/记忆向量同源"。
 *
 * <p>线程模型: WebClient 本身是异步的, 但记忆链路是同步签名(渲染 prompt 时就要结果),
 * 而调用方可能正好在 Netty event loop 线程上(Reactor 明确禁止在该线程 block)。
 * 因此所有远程调用都提交到独立线程池等待, 无论调用方线程是什么都不会抛
 * "block() ... not supported in thread reactor-http-nio"。
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

    /** 状态探测/检索/写删的超时; 记忆是"锦上添花", 宁可放弃也不能拖慢聊天 */
    private static final long STATUS_TIMEOUT_MS = 1_000L;
    private static final long SEARCH_TIMEOUT_MS = 2_000L;
    private static final long WRITE_TIMEOUT_MS = 2_000L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final long STATUS_TTL_MS = 60_000L;

    private final WebClient client;
    private final boolean configured;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "memory-vector-io");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean remoteEnabled;
    private volatile long statusCheckedAt;

    public RemoteMemoryVectorService(McpClientProperties mcpClientProperties,
                                     @Value("${app.memory.hybrid.vector-enabled:false}") boolean configured) {
        this.client = WebClient.builder().baseUrl(mcpClientProperties.getServerUrl()).build();
        this.configured = configured;
    }

    @PreDestroy
    public void shutdown() {
        ioExecutor.shutdownNow();
    }

    /**
     * 在独立线程上等待异步结果, 规避 Reactor 对 event loop 线程的 block 限制。
     *
     * @return 结果; 超时/异常返回 null, 由调用方走降级
     */
    private <T> T await(Callable<T> task, long timeoutMs) {
        Future<T> future = null;
        try {
            future = ioExecutor.submit(task);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (future != null) {
                future.cancel(true);
            }
            log.debug("[memory-vector] 远程调用失败/超时: {}", e.toString());
            return null;
        }
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
        Map<String, Object> status = await(() -> client.get()
                .uri("/api/memory-vectors/status")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block(HTTP_TIMEOUT), STATUS_TIMEOUT_MS);
        remoteEnabled = status != null && Boolean.TRUE.equals(status.get("enabled"));
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
        await(() -> client.post()
                .uri("/api/memory-vectors/upsert")
                .bodyValue(List.of(record))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block(HTTP_TIMEOUT), WRITE_TIMEOUT_MS);
    }

    @Override
    public void delete(String memoryItemId) {
        if (memoryItemId == null || memoryItemId.isBlank() || !available()) {
            return;
        }
        await(() -> client.delete()
                .uri("/api/memory-vectors/{id}", memoryItemId)
                .retrieve()
                .bodyToMono(Void.class)
                .block(HTTP_TIMEOUT), WRITE_TIMEOUT_MS);
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
        List<RemoteHit> hits = await(() -> client.post()
                .uri("/api/memory-vectors/search")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<RemoteHit>>() {
                })
                .block(HTTP_TIMEOUT), SEARCH_TIMEOUT_MS);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .map(hit -> new MemoryVectorHit(hit.id(), hit.score(), hit.content()))
                .toList();
    }

    /** mcp-server 返回体的反序列化载体 */
    private record RemoteHit(String id, double score, String content, String memoryType, String memoryKey) {
    }
}
