package uno.zhuchen.agent.tools.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 混合检索服务 — 向量路 + 关键词路双路召回, RRF 融合, 可选 Rerank 重排.
 *
 * <p>检索链路(对应 {@code dev-docs/RAG_PLAN.md} 4.1):
 * <ol>
 *   <li>双路并行召回(CompletableFuture): 向量路(Spring AI Milvus) + 关键词路(MySQL FULLTEXT ngram)</li>
 *   <li>RRF 融合: 名次融合取前 20 候选</li>
 *   <li>Rerank: DashScope 重排(query + 候选, 2 秒超时, 失败跳过)</li>
 * </ol>
 * 降级(对应 4.2 矩阵): 任一路失败仅损失该路召回并标记 degraded, 检索主链路不中断;
 * 两路全失败返回空结果 + hint, 由上层 LLM 转用 webSearch.
 *
 * <p>使用方法: 由 {@code KnowledgeSearchTool} 调用 {@link #retrieve(String, String, Integer)},
 * 返回 JSON 文本供 LLM 解析.
 */
@Slf4j
@Service
public class HybridRetrievalService {

    /** 默认返回条数 */
    private static final int DEFAULT_TOP_K = 5;

    /** 各路召回条数 */
    private static final int RECALL_TOP_K = 8;

    /** RRF 融合后候选条数(供 Rerank) */
    private static final int FUSE_CANDIDATES = 20;

    private final VectorStore vectorStore;
    private final KnowledgeChunkMapper chunkMapper;
    private final RerankService rerankService;
    private final ObjectMapper objectMapper;

    /**
     * 构造注入向量库、分块表 Mapper、重排服务与 JSON 序列化器.
     *
     * @param vectorStore    Milvus 向量库(向量路)
     * @param chunkMapper    知识分块表 Mapper(关键词 FULLTEXT 路)
     * @param rerankService  DashScope 重排服务(可降级)
     * @param objectMapper   Jackson 序列化器
     */
    public HybridRetrievalService(VectorStore vectorStore,
                                  KnowledgeChunkMapper chunkMapper,
                                  RerankService rerankService,
                                  ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.chunkMapper = chunkMapper;
        this.rerankService = rerankService;
        this.objectMapper = objectMapper;
    }

    /**
     * 混合检索主入口.
     *
     * @param query       检索问题, 如 "北京秋季适合亲子的一日游路线"
     * @param destination 目的地过滤, 如 "北京"; 为 null 时不过滤
     * @param topK        返回条数, 为 null 时取默认 5
     * @return JSON 文本: {results: [...], degraded, tookMs, hint}
     */
    public String retrieve(String query, String destination, Integer topK) {
        long start = System.currentTimeMillis();
        int limit = (topK == null || topK <= 0) ? DEFAULT_TOP_K : Math.min(topK, RECALL_TOP_K);
        Map<String, Object> payload = new LinkedHashMap<>();
        List<String> degraded = new ArrayList<>();

        // 1. 双路并行召回(各自降级, 不互相阻断)
        CompletableFuture<List<Document>> vectorFuture =
                CompletableFuture.supplyAsync(() -> fetchVector(query, destination, degraded));
        CompletableFuture<List<KnowledgeChunk>> keywordFuture =
                CompletableFuture.supplyAsync(() -> fetchKeyword(query, destination, degraded));
        List<Document> vectorDocs = vectorFuture.join();
        List<KnowledgeChunk> keywordChunks = keywordFuture.join();

        // 2. RRF 融合(依赖各路名次, 空路安全)
        List<String> vectorIds = vectorDocs.stream().map(d -> docIdOf(d)).toList();
        List<String> keywordIds = keywordChunks.stream().map(KnowledgeChunk::getId).toList();
        List<Map.Entry<String, Double>> fused = RrfFuser.fuse(List.of(vectorIds, keywordIds));
        List<String> candidateIds = fused.stream().map(Map.Entry::getKey).limit(FUSE_CANDIDATES).toList();

        // 3. 重建候选 Document(向量路优先, 否则关键词路)
        Map<String, Document> vectorIndex = new HashMap<>();
        vectorDocs.forEach(d -> vectorIndex.put(docIdOf(d), d));
        Map<String, KnowledgeChunk> keywordIndex = new HashMap<>();
        keywordChunks.forEach(c -> keywordIndex.put(c.getId(), c));
        List<Document> candidates = new ArrayList<>();
        for (String docId : candidateIds) {
            Document doc = vectorIndex.get(docId);
            if (doc == null) {
                KnowledgeChunk chunk = keywordIndex.get(docId);
                if (chunk != null) {
                    doc = new Document(docId, chunk.getContent(),
                            metadataOf(chunk.getDestination(), chunk.getTheme()));
                }
            }
            if (doc != null) {
                candidates.add(doc);
            }
        }

        if (candidates.isEmpty()) {
            payload.put("results", List.of());
            payload.put("degraded", summaryDegraded(degraded));
            payload.put("tookMs", System.currentTimeMillis() - start);
            payload.put("hint", "知识库无匹配结果, 建议转用 webSearch 检索");
            return toJson(payload);
        }

        // 4. Rerank(可降级): 成功则按重排分排序, 失败跳过用 RRF 序
        List<Document> reranked = rerankService.rerank(query, candidates, limit);
        List<Document> finals;
        if (reranked == null) {
            degraded.add("rerank");
            finals = candidates;
        } else {
            finals = reranked.isEmpty() ? candidates : reranked;
        }

        // 5. 组装结果 JSON
        List<Map<String, Object>> results = finals.stream().limit(limit).map(doc -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", doc.getText());
            item.put("docTitle", doc.getMetadata().getOrDefault("docTitle", ""));
            item.put("destination", doc.getMetadata().getOrDefault("destination", ""));
            item.put("theme", doc.getMetadata().getOrDefault("theme", ""));
            item.put("docId", doc.getMetadata().getOrDefault("docId", doc.getId()));
            item.put("source", doc.getMetadata().getOrDefault("source", "seed"));
            item.put("score", doc.getScore());
            return item;
        }).toList();

        payload.put("results", results);
        payload.put("degraded", summaryDegraded(degraded));
        payload.put("tookMs", System.currentTimeMillis() - start);
        if (results.isEmpty()) {
            payload.put("hint", "知识库无匹配结果, 建议转用 webSearch 检索");
        }
        log.info("混合检索成功: query={}, destination={}, vec={}, kw={}, cand={}, final={}, degraded={}, tookMs={}",
                query, destination, vectorDocs.size(), keywordChunks.size(), candidates.size(), results.size(),
                degraded, System.currentTimeMillis() - start);
        return toJson(payload);
    }

    /**
     * 向量路召回: Milvus 相似度检索, 返回带完整 metadata 与向量分的 Document.
     *
     * @param query       检索问题
     * @param destination 目的地过滤
     * @param degraded    降级标记收集器
     * @return 按相似度降序的 Document 列表(失败时为空)
     */
    private List<Document> fetchVector(String query, String destination, List<String> degraded) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(RECALL_TOP_K)
                    .similarityThreshold(0.0);
            if (destination != null && !destination.isBlank()) {
                builder.filterExpression("destination == '" + destination.trim().replace("'", "") + "'");
            }
            return vectorStore.similaritySearch(builder.build());
        } catch (Exception e) {
            degraded.add("milvus");
            log.warn("向量路召回降级: query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词路召回: MySQL FULLTEXT(ngram) 检索, 返回 KnowledgeChunk.
     *
     * @param query       检索问题(空/过短直接返回空)
     * @param destination 目的地过滤
     * @param degraded    降级标记收集器
     * @return 按相关度降序的分块列表(失败时为空)
     */
    private List<KnowledgeChunk> fetchKeyword(String query, String destination, List<String> degraded) {
        if (query == null || query.isBlank()) {
            degraded.add("keyword");
            return List.of();
        }
        try {
            LambdaQueryWrapper<KnowledgeChunk> qw = new LambdaQueryWrapper<>();
            if (destination != null && !destination.isBlank()) {
                qw.eq(KnowledgeChunk::getDestination, destination.trim());
            }
            // ngram FULLTEXT 自然语言模式; {0} 由 MyBatis 安全绑定
            qw.apply("MATCH(content) AGAINST ({0} IN NATURAL LANGUAGE MODE)", query)
                    .last("LIMIT " + RECALL_TOP_K);
            return chunkMapper.selectList(qw);
        } catch (Exception e) {
            degraded.add("keyword");
            log.warn("关键词路召回降级: query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    /** 从 Document 提取 docId(优先 metadata, 回退 id) */
    private String docIdOf(Document doc) {
        Object v = doc.getMetadata().get("docId");
        return v == null ? doc.getId() : v.toString();
    }

    /** 由目的地与主题构造 metadata(用于关键词路重建的 Document) */
    private Map<String, Object> metadataOf(String destination, String theme) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (destination != null) {
            metadata.put("destination", destination);
        }
        if (theme != null) {
            metadata.put("theme", theme);
        }
        return metadata;
    }

    /** 降级汇总: 无降级返回 none, 否则逗号拼接 */
    private String summaryDegraded(List<String> degraded) {
        return degraded.isEmpty() ? "none" : String.join(",", degraded);
    }

    /**
     * Map 转 JSON 文本, 序列化失败时返回极简错误 JSON.
     *
     * @param payload 待序列化数据
     * @return JSON 文本
     */
    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"results\":[],\"degraded\":\"serialize_error\"}";
        }
    }
}