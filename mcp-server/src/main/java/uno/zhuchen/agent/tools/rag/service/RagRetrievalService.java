package uno.zhuchen.agent.tools.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeChunkMapper;
import uno.zhuchen.agent.tools.rag.mapper.KnowledgeDocMapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeStats;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 知识库统计服务.
 *
 * <p>检索能力由 {@link HybridRetrievalService} 承担; 本类仅保留知识库统计
 * (文档数/分块数/按状态与来源分布), 返回强类型 {@link KnowledgeStats},
 * 由调用方(tool / controller)决定序列化方式(不再返回 String JSON 硬拼).
 */
@Slf4j
@Service
public class RagRetrievalService {

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;

    /**
     * 构造注入知识表 Mapper.
     *
     * @param docMapper   知识文档表 Mapper
     * @param chunkMapper 知识分块表 Mapper
     */
    public RagRetrievalService(KnowledgeDocMapper docMapper,
                               KnowledgeChunkMapper chunkMapper) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 知识库统计: 文档数/分块数/按索引状态与来源类型分布.
     *
     * @return 强类型统计结果; 查询异常时记录日志并返回空统计(不向上抛错)
     */
    public KnowledgeStats stats() {
        try {
            var docs = docMapper.selectList(null);
            long chunkTotal = chunkMapper.selectCount(null);
            Map<String, Long> byStatus = docs.stream().collect(Collectors.groupingBy(
                    d -> d.getStatus() == null ? "unknown" : d.getStatus(),
                    Collectors.counting()));
            Map<String, Long> bySource = docs.stream().collect(Collectors.groupingBy(
                    d -> d.getSourceType() == null ? "unknown" : d.getSourceType(),
                    Collectors.counting()));
            log.info("知识库统计: docs={}, chunks={}", docs.size(), chunkTotal);
            return new KnowledgeStats(docs.size(), chunkTotal, byStatus, bySource);
        } catch (Exception e) {
            log.warn("知识库统计降级: error={}", e.getMessage());
            return new KnowledgeStats(0L, 0L, Map.of(), Map.of());
        }
    }
}