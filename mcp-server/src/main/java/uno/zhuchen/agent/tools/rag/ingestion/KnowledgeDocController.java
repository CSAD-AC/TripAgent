package uno.zhuchen.agent.tools.rag.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeDocMapper;
import uno.zhuchen.agent.tools.rag.model.DeleteResultVO;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunk;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunkVO;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDoc;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDocPageVO;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDocVO;
import uno.zhuchen.agent.tools.rag.model.KnowledgeStats;
import uno.zhuchen.agent.tools.rag.model.KnowledgeUploadRequest;
import uno.zhuchen.agent.tools.rag.model.ReindexResult;
import uno.zhuchen.agent.tools.rag.model.UploadDocResult;
import uno.zhuchen.agent.tools.rag.service.RagRetrievalService;

import java.util.List;

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
    private final KnowledgeChunkMapper chunkMapper;
    private final IngestionService ingestionService;
    private final RagRetrievalService statsService;
    private final ReindexService reindexService;

    /**
     * 构造注入文档/分块 mapper、入库服务、统计服务与重建服务.
     */
    public KnowledgeDocController(KnowledgeDocMapper docMapper,
                                  KnowledgeChunkMapper chunkMapper,
                                  IngestionService ingestionService,
                                  RagRetrievalService statsService,
                                  ReindexService reindexService) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.ingestionService = ingestionService;
        this.statsService = statsService;
        this.reindexService = reindexService;
    }

    /**
     * 上传文档入库(异步). 立即返回 doc_id, 后台分块/向量化, 可轮询详情看状态.
     *
     * @param req {title, content, destination?, theme?, sourceRef?, sourceType?}
     * @return {docId}
     */
    @PostMapping
    public Mono<UploadDocResult> upload(@Valid @RequestBody KnowledgeUploadRequest req) {
        return Mono.fromCallable(() -> {
            String docId = ingestionService.submit(
                    req.getTitle(), req.getContent(), req.getSourceType(), req.getSourceRef(),
                    req.getDestination(), req.getTheme());
            return new UploadDocResult(docId, "pending");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 分页列表(排除已删除).
     *
     * @param page 页码(从 1 起)
     * @param size 每页条数
     * @return 强类型分页视图 {total, items:[...]}
     */
    @GetMapping
    public Mono<KnowledgeDocPageVO> list(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<KnowledgeDoc> qw = new LambdaQueryWrapper<KnowledgeDoc>()
                    .ne(KnowledgeDoc::getStatus, "deleted")
                    .orderByDesc(KnowledgeDoc::getCreatedAt);
            List<KnowledgeDoc> all = docMapper.selectList(qw);
            int from = Math.min((Math.max(1, page) - 1) * Math.max(1, size), all.size());
            int to = Math.min(from + Math.max(1, size), all.size());
            return new KnowledgeDocPageVO((long) all.size(),
                    all.subList(from, to).stream().map(KnowledgeDocController::docToVO).toList());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 详情 + 状态(用于上传后轮询).
     *
     * @param id 文档主键
     * @return 文档实体或 {code:404}
     */
    @GetMapping("/{id}")
    public Mono<KnowledgeDocVO> detail(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            KnowledgeDoc doc = docMapper.selectById(id);
            if (doc == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
            }
            return docToVO(doc);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 文档分块列表(用于阅读文档内容).
     *
     * @param id 文档主键
     * @return 分块列表(id/seq/content/destination/theme)
     */
    @GetMapping("/{id}/chunks")
    public Mono<List<KnowledgeChunkVO>> chunks(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            if (docMapper.selectById(id) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
            }
            return chunkMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeChunk>()
                                    .eq(KnowledgeChunk::getDocId, id)
                                    .orderByAsc(KnowledgeChunk::getSeq))
                    .stream()
                    .map(c -> new KnowledgeChunkVO(
                            c.getId(), c.getSeq(), c.getContent(),
                            c.getDestination(), c.getTheme()))
                    .toList();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除文档(软删 doc + 物理删分块与向量).
     *
     * @param id 文档主键
     * @return 强类型结果 {success: true} 或 {code:404}
     */
    @DeleteMapping("/{id}")
    public Mono<DeleteResultVO> delete(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            boolean ok = ingestionService.delete(id);
            if (!ok) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
            }
            return new DeleteResultVO(true);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 知识库统计(文档数/分块数/索引状态).
     *
     * @return 统计 JSON
     */
    @GetMapping("/stats")
    public Mono<KnowledgeStats> stats() {
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
    public Mono<ReindexResult> reindex() {
        return Mono.fromCallable(reindexService::reindex).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 文档实体转视图对象.
     *
     * <p>以强类型 {@link KnowledgeDocVO} 替代原先的 {@code docToMap} 手拼,
     * createdAt 转字符串规避 webflux Jackson 未注册 jsr310 的序列化问题.
     *
     * @param doc 文档实体
     * @return 视图对象
     */
    private static KnowledgeDocVO docToVO(KnowledgeDoc doc) {
        return new KnowledgeDocVO(
                doc.getId(),
                doc.getTitle(),
                doc.getSourceType(),
                doc.getSourceRef(),
                doc.getStatus(),
                doc.getChunkCount(),
                doc.getErrorMsg(),
                doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString());
    }
}