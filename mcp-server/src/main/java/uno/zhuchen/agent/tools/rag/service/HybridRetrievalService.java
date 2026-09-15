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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 混合检索服务 — 向量路 + 关键词路双路召回, RRF 融合, 可选 Rerank 重排.
 *
 * <p>检索链路(对应 {@code dev-docs/RAG_PLAN.md} 4.1):
 * <ol>
 *   <li>L1 缓存命中: 相同检索缓存(destination+query+topK)直接返回, P50 < 10ms</li>
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

    /** L1 检索缓存 TTL(ms): 知识库内容相对静态, 短缓存即可复合同一 query 复用 */
    private static final long CACHE_TTL_MS = 60_000L;

    private final VectorStore vectorStore;
    private final KnowledgeChunkMapper chunkMapper;
    private final RerankService rerankService;
    private final ObjectMapper objectMapper;

    /** L1 检索缓存: key = destination|query|limit, value = {json, expireAt} */
    private final ConcurrentHashMap<String, Object[]> cache = new ConcurrentHashMap<>();

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

        // L1 缓存: 相同检索直接返回(命中率目标 >= 80%, P50 < 10ms)
        String cacheKey = cacheKey(query, destination, limit);
        Object[] cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < (long) cached[1]) {
            log.info("L1 缓存命中: query={}, destination={}, limit={}, tookMs={}",
                    query, destination, limit, System.currentTimeMillis() - start);
            return (String) cached[0];
        }
        List<String> degraded = new ArrayList<>();

        // 1. 双路召回 + 融合 + 重排, 得到最终结果条目
        List<Map<String, Object>> results = doRetrieve(query, destination, limit, degraded);

        // 2. 目的地过滤过严(库中写法不同, 如 "凤凰" vs "凤凰古城")导致漏召回时,
        //    放宽为全库召回一次, 标记 filter_relaxed 便于观测与调参
        if (results.isEmpty() && destination != null && !destination.isBlank()) {
            degraded.add("filter_relaxed");
            results = doRetrieve(query, null, limit, degraded);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("results", results);
        payload.put("degraded", summaryDegraded(degraded));
        payload.put("tookMs", System.currentTimeMillis() - start);
        if (results.isEmpty()) {
            payload.put("hint", "知识库无匹配结果, 建议转用 webSearch 检索");
        }
        log.info("混合检索成功: query={}, destination={}, final={}, degraded={}, tookMs={}",
                query, destination, results.size(), degraded, System.currentTimeMillis() - start);

        // L1 写缓存(含空结果, 防止空结果反复穿透)
        cache.put(cacheKey, new Object[]{toJson(payload), System.currentTimeMillis() + CACHE_TTL_MS});
        return toJson(payload);
    }

    /**
     * 构造 L1 缓存 key(destination|query|limit).
     */
    private static String cacheKey(String query, String destination, int limit) {
        return (destination == null ? "" : destination) + "|" + (query == null ? "" : query) + "|" + limit;
    }

    /**
     * 核心检索: 双路并行召回 + RRF 融合 + 可选 Rerank, 返回结果条目.
     *
     * @param query       检索问题
     * @param destination 目的地过滤(可为 null, 表示全库)
     * @param limit       返回条数
     * @param degraded    降级标记收集器
     * @return 结果条目列表(可能为空)
     */
    private List<Map<String, Object>> doRetrieve(String query, String destination, int limit, List<String> degraded) {
        // 双路并行召回(各自降级, 不互相阻断)
        CompletableFuture<List<Document>> vectorFuture =
                CompletableFuture.supplyAsync(() -> fetchVector(query, destination, degraded));
        CompletableFuture<List<KnowledgeChunk>> keywordFuture =
                CompletableFuture.supplyAsync(() -> fetchKeyword(query, destination, degraded));
        List<Document> vectorDocs = vectorFuture.join();
        List<KnowledgeChunk> keywordChunks = keywordFuture.join();

        // RRF 融合
        List<String> vectorIds = vectorDocs.stream().map(this::docIdOf).toList();
        List<String> keywordIds = keywordChunks.stream().map(KnowledgeChunk::getId).toList();
        List<Map.Entry<String, Double>> fused = RrfFuser.fuse(List.of(vectorIds, keywordIds));
        List<String> candidateIds = fused.stream().map(Map.Entry::getKey).limit(FUSE_CANDIDATES).toList();

        // 重建候选 Document(向量路优先, 否则关键词路)
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
            return List.of();
        }

        // Rerank(可降级): 成功则按重排分排序, 失败跳过用 RRF 序
        List<Document> reranked = rerankService.rerank(query, candidates, limit);
        List<Document> finals;
        if (reranked == null) {
            degraded.add("rerank");
            finals = candidates;
        } else {
            finals = reranked.isEmpty() ? candidates : reranked;
        }

        // 组装结果条目
        return finals.stream().limit(limit).map(doc -> {
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