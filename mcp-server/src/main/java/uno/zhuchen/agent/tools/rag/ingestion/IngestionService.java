package uno.zhuchen.agent.tools.rag.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeDocMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunk;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDoc;

import java.util.List;

/**
 * 知识文档管理服务 — 文档提交入库与删除, 批量重建等.
 *
 * <p>提交: 同步建 doc(status=pending) 并调度 {@link IngestionWorker} 异步入库, 立即返回 doc_id 供轮询;
 * 删除: 软删 doc + 物理删 chunk + 同步删 Milvus 向量(按 doc_id 关联的 chunk_id).
 * 分块参数集中由 yml(rag.ingestion.*) 配置, 改参后可用 reindex 重建验证.
 */
@Slf4j
@Service
public class IngestionService {

    private final VectorStore vectorStore;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final IngestionWorker ingestionWorker;

    /** 分块大小(字符数) */
    private final int chunkSize;

    /** 分块重叠(字符数) */
    private final int chunkOverlap;

    /** 极短块合并阈值 */
    private final int minChunk;

    /**
     * 构造注入各依赖与分块参数.
     */
    public IngestionService(VectorStore vectorStore,
                            KnowledgeDocMapper docMapper,
                            KnowledgeChunkMapper chunkMapper,
                            IngestionWorker ingestionWorker,
                            @Value("${rag.ingestion.chunk-size:500}") Integer chunkSize,
                            @Value("${rag.ingestion.chunk-overlap:60}") Integer chunkOverlap,
                            @Value("${rag.ingestion.min-chunk:50}") Integer minChunk) {
        this.vectorStore = vectorStore;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.ingestionWorker = ingestionWorker;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.minChunk = minChunk;
    }

    /**
     * 提交文档入库(异步): 建 pending 文档并调度后台分块/向量化.
     *
     * @param title       文档标题
     * @param content     文档原文
     * @param sourceType  来源类型(manual/self_precip/graph_report/url)
     * @param sourceRef   溯源引用(traceId/conversationId/URL, 可空)
     * @param destination 目的地 metadata(可空)
     * @param theme       主题 metadata(可空)
     * @return 新建文档的 doc_id
     */
    public String submit(String title, String content, String sourceType, String sourceRef,
                         String destination, String theme) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(title);
        doc.setSourceType(sourceType == null ? "manual" : sourceType);
        doc.setSourceRef(sourceRef);
        doc.setStatus("pending");
        doc.setChunkCount(0);
        docMapper.insert(doc);
        log.info("文档已提交排队入库: docId={}, title={}, sourceType={}", doc.getId(), title, doc.getSourceType());
        ingestionWorker.processDocument(doc.getId(), content, destination, theme, chunkSize, chunkOverlap, minChunk);
        return doc.getId();
    }

    /**
     * 删除文档: 软删 doc, 物理删其分块与 Milvus 向量.
     *
     * @param docId 文档主键
     * @return true 删除成功, false 文档不存在
     */
    @Transactional
    public boolean delete(String docId) {
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            return false;
        }
        if ("deleted".equals(doc.getStatus())) {
            return true;
        }
        List<KnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, docId));
        List<String> chunkIds = chunks.stream().map(KnowledgeChunk::getId).toList();
        if (!chunkIds.isEmpty()) {
            try {
                vectorStore.delete(chunkIds);
            } catch (Exception e) {
                log.warn("删除文档向量失败(忽略, 源数据仍删): docId={}, error={}", docId, e.getMessage());
            }
            chunkMapper.deleteByIds(chunkIds);
        }
        doc.setStatus("deleted");
        docMapper.updateById(doc);
        log.info("文档已删除: docId={}, title={}, chunks={}", docId, doc.getTitle(), chunkIds.size());
        return true;
    }
}