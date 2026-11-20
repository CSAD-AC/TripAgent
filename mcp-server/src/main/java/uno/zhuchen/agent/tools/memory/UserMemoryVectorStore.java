package uno.zhuchen.agent.tools.memory;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户记忆向量库 — 独立 collection 的 Milvus 读写封装。
 *
 * <p>为什么不复用 Spring AI 自动配置的 {@code VectorStore} bean:
 * 自动配置只产出知识库用的那一个 collection, 再声明一个同类型 bean 会让
 * {@code HybridRetrievalService} 的按类型注入产生歧义。这里手动构建
 * {@link MilvusVectorStore} 实例并延迟初始化, 两者互不干扰。
 *
 * <p>延迟初始化是有意为之: {@code app.memory-vector.enabled=false}(默认)时不连 Milvus,
 * 本地没有 Milvus 也能正常启动 mcp-server; 首次真正读写时才建连, 连不上只让本次记忆
 * 向量操作降级, 不影响知识库检索与 MCP 工具。
 */
@Slf4j
@Component
public class UserMemoryVectorStore {

    private static final int DEFAULT_TOP_K = 8;

    private final UserMemoryVectorProperties props;
    private final EmbeddingModel embeddingModel;

    private volatile MilvusVectorStore store;

    public UserMemoryVectorStore(UserMemoryVectorProperties props, EmbeddingModel embeddingModel) {
        this.props = props;
        this.embeddingModel = embeddingModel;
    }

    public boolean enabled() {
        return props.isEnabled();
    }

    public String collectionName() {
        return props.getCollectionName();
    }

    public int embeddingDimension() {
        return props.getEmbeddingDimension();
    }

    /** 写入(同 id 覆盖写): 先按 id 删后插, 避免 Milvus 主键冲突。 */
    public void upsert(List<MemoryVectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        MilvusVectorStore vectorStore = vectorStore();
        List<String> ids = records.stream().map(MemoryVectorRecord::id).toList();
        List<Document> documents = new ArrayList<>();
        for (MemoryVectorRecord record : records) {
            documents.add(new Document(record.id(), record.content(), metadataOf(record)));
        }
        try {
            vectorStore.delete(ids);
        } catch (Exception e) {
            log.debug("[memory-vector] 覆盖写前删除跳过: ids={}, error={}", ids, e.getMessage());
        }
        vectorStore.add(documents);
        log.info("[memory-vector] 写入完成: collection={}, count={}", props.getCollectionName(), documents.size());
    }

    public void delete(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        vectorStore().delete(List.of(id));
        log.info("[memory-vector] 删除完成: collection={}, id={}", props.getCollectionName(), id);
    }

    /**
     * 向量召回: 服务端强制拼 tenant/user/status 过滤, 调用方无法越权跨用户召回。
     */
    public List<MemoryVectorHit> search(MemoryVectorSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return List.of();
        }
        String filter = filterExpression(request.tenantId(), request.userId(), request.projectId());
        if (filter.isEmpty()) {
            // 缺少租户/用户维度时直接拒绝, 防止全库召回造成越权
            log.warn("[memory-vector] 拒绝无 tenant/user 过滤的检索请求");
            return List.of();
        }
        int topK = request.topK() == null || request.topK() <= 0 ? DEFAULT_TOP_K : Math.min(request.topK(), 50);
        double minScore = request.minScore() == null ? 0.0 : request.minScore();
        SearchRequest searchRequest = SearchRequest.builder()
                .query(request.query())
                .topK(topK)
                .similarityThreshold(minScore)
                .filterExpression(filter)
                .build();
        List<Document> documents = vectorStore().similaritySearch(searchRequest);
        List<MemoryVectorHit> hits = new ArrayList<>();
        for (Document document : documents) {
            hits.add(new MemoryVectorHit(
                    document.getId(),
                    document.getScore() == null ? 0.0 : document.getScore(),
                    document.getText(),
                    metaStr(document, "memory_type"),
                    metaStr(document, "memory_key")));
        }
        log.info("[memory-vector] 召回完成: query={}, hits={}, filter={}", request.query(), hits.size(), filter);
        return hits;
    }

    private Map<String, Object> metadataOf(MemoryVectorRecord record) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant_id", safe(record.tenantId(), "default"));
        metadata.put("user_id", safe(record.userId(), ""));
        metadata.put("project_id", safe(record.projectId(), ""));
        metadata.put("memory_type", safe(record.memoryType(), ""));
        metadata.put("memory_key", safe(record.memoryKey(), ""));
        metadata.put("status", "active");
        return metadata;
    }

    /**
     * 构造 Milvus 过滤表达式。
     *
     * <p>取值只保留字母/数字/下划线/连字符, 杜绝把引号、反斜杠带进表达式造成注入或语法错误。
     */
    static String filterExpression(String tenantId, String userId, String projectId) {
        String tenant = literal(tenantId);
        String user = literal(userId);
        if (tenant.isEmpty() || user.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("tenant_id == '").append(tenant).append("'")
                .append(" && user_id == '").append(user).append("'")
                .append(" && status == 'active'");
        String project = literal(projectId);
        if (!project.isEmpty()) {
            builder.append(" && project_id == '").append(project).append("'");
        }
        return builder.toString();
    }

    private static String literal(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("[^\\p{L}\\p{N}_\\-]", "");
    }

    private static String safe(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static String metaStr(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

    /** 延迟建连: 首次读写才连接 Milvus, 未启用时直接报错由调用方降级。 */
    private MilvusVectorStore vectorStore() {
        MilvusVectorStore local = store;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (store != null) {
                return store;
            }
            if (!props.isEnabled()) {
                throw new IllegalStateException("记忆向量库未启用(app.memory-vector.enabled=false)");
            }
            store = createStore();
            return store;
        }
    }

    private MilvusVectorStore createStore() {
        ConnectParam.Builder connect = ConnectParam.newBuilder()
                .withHost(props.getHost())
                .withPort(props.getPort())
                .withDatabaseName(props.getDatabaseName())
                .withConnectTimeout(3000, TimeUnit.MILLISECONDS)
                .withRpcDeadline(5000, TimeUnit.MILLISECONDS);
        if (props.getToken() != null && !props.getToken().isBlank()) {
            connect.withToken(props.getToken());
        }
        MilvusServiceClient client = new MilvusServiceClient(connect.build());
        MilvusVectorStore vectorStore = MilvusVectorStore.builder(client, embeddingModel)
                .collectionName(props.getCollectionName())
                .databaseName(props.getDatabaseName())
                .embeddingDimension(props.getEmbeddingDimension())
                .indexType(IndexType.valueOf(props.getIndexType()))
                .metricType(MetricType.valueOf(props.getMetricType()))
                .initializeSchema(true)
                .build();
        try {
            // 手动构建不走 Spring 生命周期, 需显式触发 collection/index 初始化
            vectorStore.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("记忆向量 collection 初始化失败: " + e.getMessage(), e);
        }
        log.info("[memory-vector] Milvus 建连成功: host={}, collection={}, dim={}",
                props.getHost(), props.getCollectionName(), props.getEmbeddingDimension());
        return vectorStore;
    }
}
