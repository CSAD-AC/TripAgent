package uno.zhuchen.agent.tools.rag.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeDocMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDoc;
import uno.zhuchen.agent.tools.rag.service.RagRetrievalService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库文档管理 REST — 上传/列表/详情/删除.
 *
 * <p>该接口为内部服务(:8081), 由 agent-core(:8080) 的转发层对外暴露以统一 API 入口,
 * 存储访问(MyBatis)为阻塞操作, 故每个方法用 {@code Mono + subscribeOn(boundedElastic)}
 * 包裹, 避免在 Netty event loop 线程上阻塞.
 *
 * <p>接口语义对应 {@code RAG_PLAN.md} 5.3.
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge/documents")
public class KnowledgeDocController {

    private final KnowledgeDocMapper docMapper;
    private final IngestionService ingestionService;
    private final RagRetrievalService statsService;
    private final ReindexService reindexService;

    /**
     * 构造注入文档 mapper、入库服务、统计服务与重建服务.
     */
    public KnowledgeDocController(KnowledgeDocMapper docMapper,
                                  IngestionService ingestionService,
                                  RagRetrievalService statsService,
                                  ReindexService reindexService) {
        this.docMapper = docMapper;
        this.ingestionService = ingestionService;
        this.statsService = statsService;
        this.reindexService = reindexService;
    }

    /**
     * 上传文档入库(异步). 立即返回 doc_id, 后台分块/向量化, 可轮询详情看状态.
     *
     * @param body {title, content, destination?, theme?, sourceRef?, sourceType?}
     * @return {docId}
     */
    @PostMapping
    public Mono<Map<String, Object>> upload(@RequestBody Map<String, Object> body) {
        return Mono.fromCallable(() -> {
            String title = str(body.get("title"));
            String content = str(body.get("content"));
            String destination = str(body.get("destination"));
            String theme = str(body.get("theme"));
            String sourceRef = str(body.get("sourceRef"));
            String sourceType = str(body.get("sourceType"));
            if (title == null || title.isBlank() || content == null || content.isBlank()) {
                return err(400, "title 与 content 必填");
            }
            String docId = ingestionService.submit(title, content, sourceType, sourceRef, destination, theme);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("docId", docId);
            resp.put("status", "pending");
            return resp;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 分页列表(排除已删除).
     *
     * @param page 页码(从 1 起)
     * @param size 每页条数
     * @return {total, items:[...]}
     */
    @GetMapping
    public Mono<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<KnowledgeDoc> qw = new LambdaQueryWrapper<KnowledgeDoc>()
                    .ne(KnowledgeDoc::getStatus, "deleted")
                    .orderByDesc(KnowledgeDoc::getCreatedAt);
            List<KnowledgeDoc> all = docMapper.selectList(qw);
            int from = Math.min((Math.max(1, page) - 1) * Math.max(1, size), all.size());
            int to = Math.min(from + Math.max(1, size), all.size());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("total", all.size());
            resp.put("items", all.subList(from, to).stream().map(KnowledgeDocController::docToMap).toList());
            return resp;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 详情 + 状态(用于上传后轮询).
     *
     * @param id 文档主键
     * @return 文档实体或 {code:404}
     */
    @GetMapping("/{id}")
    public Mono<Map<String, Object>> detail(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            KnowledgeDoc doc = docMapper.selectById(id);
            return doc != null ? docToMap(doc) : err(404, "文档不存在");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除文档(软删 doc + 物理删分块与向量).
     *
     * @param id 文档主键
     * @return {success: true} 或 {code:404}
     */
    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            boolean ok = ingestionService.delete(id);
            if (!ok) {
                return err(404, "文档不存在");
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            return resp;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 统一错误响应体.
     *
     * @param code    业务码
     * @param message 错误信息
     * @return 错误 Map
     */
    private static Map<String, Object> err(int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    /**
     * 知识库统计(文档数/分块数/索引状态).
     *
     * @return 统计 JSON
     */
    @GetMapping("/stats")
    public Mono<String> stats() {
        return Mono.fromCallable(statsService::stats).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 全量重建索引(MySQL 分块重新向量化写回 Milvus).
     *
     * <p>用于换 Embedding 模型 / 调分块参数 / 灾后恢复. 重建进行中返回 {status:busy}.
     *
     * @return {status, total, success, failed}
     */
    @PostMapping("/reindex")
    public Mono<Map<String, Object>> reindex() {
        return Mono.fromCallable(reindexService::reindex).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 文档实体转可序列化 Map.
     *
     * <p>手动拼字段而非直接返回实体, 规避 webflux Jackson 未注册 jsr310
     * 时对 LocalDateTime 序列化抛错的问题.
     *
     * @param doc 文档实体
     * @return 字段 Map(createdAt 转字符串)
     */
    private static Map<String, Object> docToMap(KnowledgeDoc doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.getId());
        m.put("title", doc.getTitle());
        m.put("sourceType", doc.getSourceType());
        m.put("sourceRef", doc.getSourceRef());
        m.put("status", doc.getStatus());
        m.put("chunkCount", doc.getChunkCount());
        m.put("errorMsg", doc.getErrorMsg());
        m.put("createdAt", doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString());
        return m;
    }

    /** Map 取值容错转字符串 */
    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}