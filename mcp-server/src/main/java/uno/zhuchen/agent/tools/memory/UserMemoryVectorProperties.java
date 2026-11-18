package uno.zhuchen.agent.tools.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户长期记忆向量库配置({@code app.memory-vector.*})。
 *
 * <p>与知识库 RAG 的 {@code knowledge_chunk} collection 完全隔离:
 * 记忆条目短、更新频繁、按用户隔离, 混在知识库里会导致重建索引时互相污染。
 */
@ConfigurationProperties(prefix = "app.memory-vector")
public class UserMemoryVectorProperties {

    /** 是否启用记忆向量库; 关闭时所有接口快速失败/空返回, 不连 Milvus */
    private boolean enabled = false;

    private String collectionName = "user_memory_episodic_v1";
    private int embeddingDimension = 1024;
    private String host = "localhost";
    private int port = 19530;
    private String databaseName = "default";
    private String indexType = "IVF_FLAT";
    private String metricType = "COSINE";
    private String token;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getIndexType() {
        return indexType;
    }

    public void setIndexType(String indexType) {
        this.indexType = indexType;
    }

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
