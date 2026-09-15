package uno.zhuchen.agent.tools.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeDocMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeStats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识检索服务 — 向量检索 + 全链路降级.
 *
 * <p>检索链路: query 文本 -> VectorStore 内部 Embedding -> Milvus 相似度检索 -> 结果组装 JSON.
 * 降级策略: Embedding 或 Milvus 任一环节异常时, 不向上抛错, 返回带 degraded 标志的空结果
 * 说明文案, 由上层 LLM 自行决定转用 webSearch 等外部检索工具.
 *
 * <p>使用方法: 由 {@code KnowledgeSearchTool} 调用 {@link #retrieve(String, String, Integer)},
 * 返回 JSON 文本供 LLM 解析.
 */
@Slf4j
@Service
public class RagRetrievalService {

    /** 默认返回条数 */
    private static final int DEFAULT_TOP_K = 5;

    /** 向量检索召回条数(取 topK 的 1.6 倍, 给后续过滤/重排留余量) */
    private static final int RECALL_TOP_K = 8;

    private final VectorStore vectorStore;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    /**
     * 构造注入向量库与知识表 Mapper.
     *
     * @param vectorStore  Milvus 向量库 (Spring AI 自动装配)
     * @param docMapper    知识文档表 Mapper
     * @param chunkMapper  知识分块表 Mapper
     * @param objectMapper Jackson 序列化器 (复用全局配置)
     */
    public RagRetrievalService(VectorStore vectorStore,
                               KnowledgeDocMapper docMapper,
                               KnowledgeChunkMapper chunkMapper,
                               ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 知识语义检索主入口.
     *
     * <p>流程: 组装 SearchRequest(可选目的地过滤) -> Milvus 向量检索 -> 组装结果 JSON.
     * 异常全兜底: 返回 degraded=rag_unavailable 的空结果 + 转用 webSearch 的提示.
     *
     * @param query       检索问题, 如 "北京秋季适合亲子的一日游路线"
     * @param destination 目的地过滤, 如 "北京"; 为 null 时不过滤
     * @param topK        返回条数, 为 null 时取默认 5
     * @return JSON 文本: {results: [{content, docTitle, destination, theme, score, docId}], degraded, tookMs, hint}
     */
    public String retrieve(String query, String destination, Integer topK) {
        long start = System.currentTimeMillis();
        int limit = (topK == null || topK <= 0) ? DEFAULT_TOP_K : Math.min(topK, RECALL_TOP_K);
        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(limit)
                    .similarityThreshold(0.0);
            if (destination != null && !destination.isBlank()) {
                builder.filterExpression("destination == '" + destination.trim().replace("'", "") + "'");
            }

            List<Document> hits = vectorStore.similaritySearch(builder.build());
            List<Map<String, Object>> results = new ArrayList<>();
            for (Document doc : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("content", doc.getText());
                item.put("docTitle", doc.getMetadata().getOrDefault("docTitle", ""));
                item.put("destination", doc.getMetadata().getOrDefault("destination", ""));
                item.put("theme", doc.getMetadata().getOrDefault("theme", ""));
                item.put("docId", doc.getMetadata().getOrDefault("docId", ""));
                item.put("score", doc.getScore());
                results.add(item);
            }
            payload.put("results", results);
            payload.put("degraded", "none");
            payload.put("tookMs", System.currentTimeMillis() - start);
            if (results.isEmpty()) {
                payload.put("hint", "知识库无匹配结果, 建议转用 webSearch 检索");
            }
            log.info("知识检索成功: query={}, destination={}, hits={}, tookMs={}",
                    query, destination, results.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 降级: 不向上抛错, 保证调用方(三模式 Agent)主流程不中断
            payload.put("results", List.of());
            payload.put("degraded", "rag_unavailable");
            payload.put("tookMs", System.currentTimeMillis() - start);
            payload.put("hint", "知识库暂不可用, 建议转用 webSearch 检索");
            log.warn("知识检索降级: query={}, destination={}, error={}", query, destination, e.getMessage());
        }
        return toJson(payload);
    }

    /**
     * 知识库统计信息.
     *
     * @return JSON 文本: {docTotal, chunkTotal, docByStatus, docBySourceType}
     */
    public String stats() {
        try {
            var docs = docMapper.selectList(null);
            long chunkTotal = chunkMapper.selectCount(null);
            KnowledgeStats stats = new KnowledgeStats(
                    docs.size(),
                    chunkTotal,
                    docs.stream().collect(java.util.stream.Collectors.groupingBy(
                            d -> d.getStatus() == null ? "unknown" : d.getStatus(),
                            java.util.stream.Collectors.counting())),
                    docs.stream().collect(java.util.stream.Collectors.groupingBy(
                            d -> d.getSourceType() == null ? "unknown" : d.getSourceType(),
                            java.util.stream.Collectors.counting())));
            log.info("知识库统计: docs={}, chunks={}", docs.size(), chunkTotal);
            return toJson(stats);
        } catch (Exception e) {
            log.warn("知识库统计降级: error={}", e.getMessage());
            return "{\"error\":\"stats_unavailable\"}";
        }
    }

    /**
     * 对象转 JSON 文本, 序列化失败时返回极简错误 JSON.
     *
     * @param payload 待序列化数据(强类型响应体)
     * @return JSON 文本
     */
    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"results\":[],\"degraded\":\"serialize_error\"}";
        }
    }
}
