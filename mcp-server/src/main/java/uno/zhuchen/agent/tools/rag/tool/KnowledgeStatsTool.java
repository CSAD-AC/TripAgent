package uno.zhuchen.agent.tools.rag.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.rag.service.RagRetrievalService;

/**
 * 知识库统计 MCP 工具 — 查询知识库规模与索引健康度.
 *
 * <p>返回文档总数/分块总数/按状态与按来源类型的分布,
 * 用于了解知识库当前可用规模. 开关由 mcp.tool-groups.rag.tools.knowledge-stats 控制.
 */
@Slf4j
@Component
public class KnowledgeStatsTool {

    private final RagRetrievalService retrievalService;

    /**
     * 构造注入检索服务.
     *
     * @param retrievalService RAG 检索服务 (提供 stats 能力)
     */
    public KnowledgeStatsTool(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /**
     * 知识库统计查询.
     *
     * @return JSON 文本: {docTotal, chunkTotal, docByStatus, docBySourceType}
     */
    @Tool(description = "知识库统计 — 查询自有知识库的文档数/分块数/索引状态分布, 了解知识库规模与健康度")
    public String knowledgeStats() {
        log.info("知识库统计工具调用");
        return retrievalService.stats();
    }
}
