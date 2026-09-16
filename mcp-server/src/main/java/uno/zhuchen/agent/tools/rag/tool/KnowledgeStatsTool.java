package uno.zhuchen.agent.tools.rag.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.rag.model.KnowledgeStats;
import uno.zhuchen.agent.tools.rag.service.RagRetrievalService;

/**
 * 知识库统计 MCP 工具 — 查询知识库规模与索引健康度.
 *
 * <p>返回文档总数/分块总数/按状态与按来源类型的分布,
 * 用于了解知识库当前可用规模. 开关由 mcp.tool-groups.rag.tools.knowledge-stats 控制.
 *
 * <p>统计由 {@link RagRetrievalService} 承担并返回强类型 {@link KnowledgeStats};
 * 本工具仅按 MCP 协议把统计序列化为 String.
 */
@Slf4j
@Component
public class KnowledgeStatsTool {

    private final RagRetrievalService retrievalService;
    private final ObjectMapper objectMapper;

    /**
     * 构造注入统计服务与 JSON 序列化器.
     *
     * @param retrievalService RAG 统计服务
     * @param objectMapper     JSON 序列化器(MCP 协议要求返回 String)
     */
    public KnowledgeStatsTool(RagRetrievalService retrievalService,
                              ObjectMapper objectMapper) {
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
    }

    /**
     * 知识库统计查询.
     *
     * @return JSON 文本: {docTotal, chunkTotal, docByStatus, docBySourceType}
     */
    @Tool(description = "知识库统计 — 查询自有知识库的文档数/分块数/索引状态分布, 了解知识库规模与健康度")
    public String knowledgeStats() {
        log.info("知识库统计工具调用");
        KnowledgeStats stats = retrievalService.stats();
        try {
            return objectMapper.writeValueAsString(stats);
        } catch (Exception e) {
            log.warn("知识库统计序列化失败: error={}", e.getMessage());
            return "{\"error\":\"serialize_error\"}";
        }
    }
}