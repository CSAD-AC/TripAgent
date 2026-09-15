package uno.zhuchen.agent.tools.rag.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeDocMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunk;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDoc;
import uno.zhuchen.agent.tools.rag.model.ReindexResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库全量重建服务 — 将 MySQL 中非删除文档的分块重新向量化写回 Milvus.
 *
 * <p>适用场景: 更换 Embedding 模型 / 调整分块参数 / 灾后重建索引.
 * MySQL 为源数据保持不变, 仅重建 Milvus 向量(按 chunk_id upsert 覆盖).
 * 提供 {@link #isRebuilding()} 供调用方在重建期间返回 409 拒绝并发.
 *
 * <p>该服务为同步阻塞执行, 由调用方(controller)放到 boundedElastic 线程执行.
 */
@Slf4j
@Service
public class ReindexService {

    /** DashScope embedding 单次请求批量上限 */
    private static final int EMBED_BATCH_SIZE = 10;

    private final VectorStore vectorStore;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;

    /** 重建进行中标志(保证不并发) */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /**
     * 构造注入向量库与知识表 Mapper.
     */
    public ReindexService(VectorStore vectorStore,
                          KnowledgeDocMapper docMapper,
                          KnowledgeChunkMapper chunkMapper) {
        this.vectorStore = vectorStore;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
    }

    /** 是否正在重建(供 controller 返回 409) */
    public boolean isRebuilding() {
        return rebuilding.get();
    }

    /**
     * 全量重建: 重新向量化所有非删除文档的分块并写回 Milvus.
     *
     * @return {@link ReindexResult}; 正在重建时 status=busy
     */
    public ReindexResult reindex() {
        if (!rebuilding.compareAndSet(false, true)) {
            log.warn("重建已在进行中, 拒绝并发请求");
            return ReindexResult.busy();
        }
        int total = 0;
        int success = 0;
        try {
            // 取出所有非删除文档(排除 deleted)
            List<KnowledgeDoc> docs = docMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDoc>().ne(KnowledgeDoc::getStatus, "deleted"));
            for (KnowledgeDoc doc : docs) {
                try {
                    List<KnowledgeChunk> chunks = chunkMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, doc.getId()));
                    int done = vectorizeChunks(doc, chunks);
                    success += done;
                    total += chunks.size();
                    log.info("重建文档: docId={}, title={}, chunks={}", doc.getId(), doc.getTitle(), done);
                } catch (Exception e) {
                    log.warn("重建文档失败(跳过, 其余继续): docId={}, error={}", doc.getId(), e.getMessage());
                }
            }
            log.info("知识库全量重建完成: docs={}, chunksTotal={}, success={}", docs.size(), total, success);
            return ReindexResult.done(total, success, total - success);
        } finally {
            rebuilding.set(false);
        }
    }

    /**
     * 向量化单个文档的全部分块并写回 Milvus(按 chunk_id upsert).
     *
     * @param doc    文档实体(取标题)
     * @param chunks 该文档的分块
     * @return 成功向量化数量
     */
    private int vectorizeChunks(KnowledgeDoc doc, List<KnowledgeChunk> chunks) {
        if (chunks.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
            List<KnowledgeChunk> batch = chunks.subList(i, Math.min(i + EMBED_BATCH_SIZE, chunks.size()));
            List<Document> documents = new ArrayList<>();
            for (KnowledgeChunk c : batch) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("docId", doc.getId());
                metadata.put("docTitle", doc.getTitle());
                metadata.put("destination", c.getDestination() == null ? "" : c.getDestination());
                metadata.put("theme", c.getTheme() == null ? "" : c.getTheme());
                metadata.put("source", doc.getSourceType());
                metadata.put("seq", c.getSeq());
                documents.add(new Document(c.getId(), c.getContent(), metadata));
            }
            vectorStore.add(documents);
            for (KnowledgeChunk c : batch) {
                c.setEmbeddingStatus("indexed");
                chunkMapper.updateById(c);
            }
            done += batch.size();
        }
        return done;
    }
}