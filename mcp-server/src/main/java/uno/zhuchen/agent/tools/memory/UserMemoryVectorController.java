package uno.zhuchen.agent.tools.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆向量库内部 REST — agent-core 通过它读写用户记忆向量。
 *
 * <p>只在内网(默认 :8081)暴露, 不直接对前端开放; 对外统一走 agent-core 的
 * {@code /api/memory*}。Milvus 调用是阻塞 IO, 每个方法都用 boundedElastic 包裹。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory-vectors")
public class UserMemoryVectorController {

    private final UserMemoryVectorStore store;

    public UserMemoryVectorController(UserMemoryVectorStore store) {
        this.store = store;
    }

    /** 状态自检: 供 agent-core 判断是否真正启用了语义召回 */
    @GetMapping("/status")
    public Mono<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", store.enabled());
        body.put("collection", store.collectionName());
        body.put("embeddingDimension", store.embeddingDimension());
        return Mono.just(body);
    }

    @PostMapping("/upsert")
    public Mono<Map<String, Object>> upsert(@RequestBody List<MemoryVectorRecord> records) {
        return Mono.fromCallable(() -> {
                    store.upsert(records);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("ok", true);
                    body.put("count", records == null ? 0 : records.size());
                    return body;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> degraded("写入失败", e));
    }

    @PostMapping("/search")
    public Mono<List<MemoryVectorHit>> search(@RequestBody MemoryVectorSearchRequest request) {
        return Mono.fromCallable(() -> store.search(request))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> degraded("检索失败", e));
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable String id) {
        return Mono.fromCallable(() -> {
                    store.delete(id);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("ok", true);
                    return body;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> degraded("删除失败", e));
    }

    private ResponseStatusException degraded(String action, Throwable e) {
        log.warn("[memory-vector] {}: error={}", action, e.getMessage());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, action + ": " + e.getMessage(), e);
    }
}
