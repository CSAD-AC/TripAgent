package uno.zhuchen.agent.tools.rag.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeDocMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunk;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDoc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识文档异步入库执办 — 在 ragIngestionExecutor 单线程上执行分块/向量化/落库.
 *
 * <p>流程(对应 {@code RAG_PLAN.md} 3.3): 结构化分块 {@link Chunker}
 * → 批量 Embedding 写入 Milvus(每次 10 条, DashScope 批量上限) → MySQL 落 chunk,
 * 全部成功回写 doc.status=indexed, 任一步异常回写 failed 并记录 error_msg.
 *
 * <p>按 doc 粒度隔离: 本批 doc 的处理失败不影响其它已索引文档.
 */
@Slf4j
@Component
public class IngestionWorker {

    /** DashScope embedding 单次请求批量上限 */
    private static final int EMBED_BATCH_SIZE = 10;

    private final VectorStore vectorStore;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;

    /**
     * 构造注入向量库与知识表 Mapper.
     *
     * @param vectorStore  Milvus 向量库
     * @param docMapper    知识文档表 Mapper
     * @param chunkMapper  知识分块表 Mapper
     */
    public IngestionWorker(VectorStore vectorStore,
                           KnowledgeDocMapper docMapper,
                           KnowledgeChunkMapper chunkMapper) {
        this.vectorStore = vectorStore;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 异步处理单个已落库为 pending 的文档: 分块 → 向量化 → 落 chunk → 回写状态.
     *
     * @param docId       文档主键(已存在, status=pending)
     * @param content     文档原文
     * @param destination 目的地 metadata(可为空)
     * @param theme       主题 metadata(可为空)
     * @param chunkSize   分块大小(字符数)
     * @param overlap     分块重叠
     * @param minChunk    极短块合并阈值
     */
    @Async("ragIngestionExecutor")
    public void processDocument(String docId, String content, String destination, String theme,
                                int chunkSize, int overlap, int minChunk) {
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            log.warn("文档不存在, 中止入库: docId={}", docId);
            return;
        }
        try {
            List<String> subtasks = Chunker.chunk(content, chunkSize, overlap, minChunk);
            List<KnowledgeChunk> chunks = new ArrayList<>();
            List<Document> documents = new ArrayList<>();
            int seq = 0;
            for (String text : subtasks) {
                String chunkContent = text.trim();
                if (chunkContent.isEmpty()) {
                    continue;
                }
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocId(docId);
                chunk.setSeq(seq);
                chunk.setContent(chunkContent);
                chunk.setCharCount(chunkContent.length());
                chunk.setDestination(destination);
                chunk.setTheme(theme);
                chunk.setEmbeddingStatus("pending");
                chunkMapper.insert(chunk);
                chunks.add(chunk);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("docId", docId);
                metadata.put("docTitle", doc.getTitle());
                metadata.put("destination", destination == null ? "" : destination);
                metadata.put("theme", theme == null ? "" : theme);
                metadata.put("source", doc.getSourceType());
                metadata.put("seq", seq);
                documents.add(new Document(chunk.getId(), chunkContent, metadata));
                seq++;
            }

            // 分批向量化写入 Milvus
            for (int i = 0; i < documents.size(); i += EMBED_BATCH_SIZE) {
                List<Document> batch = documents.subList(i, Math.min(i + EMBED_BATCH_SIZE, documents.size()));
                vectorStore.add(batch);
            }
            for (KnowledgeChunk chunk : chunks) {
                chunk.setEmbeddingStatus("indexed");
                chunkMapper.updateById(chunk);
            }
            doc.setChunkCount(chunks.size());
            doc.setStatus("indexed");
            docMapper.updateById(doc);
            log.info("文档入库完成: docId={}, chunks={}", docId, chunks.size());
        } catch (Exception e) {
            doc.setStatus("failed");
            doc.setErrorMsg(truncate(e.getMessage()));
            docMapper.updateById(doc);
            log.warn("文档入库失败(已隔离): docId={}, error={}", docId, e.getMessage());
        }
    }

    /** 截断错误信息到列宽上限 */
    private String truncate(String msg) {
        if (msg == null) {
            return "unknown";
        }
        return msg.length() > 1024 ? msg.substring(0, 1024) : msg;
    }
}