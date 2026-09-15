package uno.zhuchen.agent.tools.rag.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.tools.rag.service.RagRetrievalService;

/**
 * 知识库检索 MCP 工具 — 从自有旅游知识库语义检索.
 *
 * <p>知识库沉淀景点/美食/行程经验/历史规划报告等内部知识,
 * LLM 在回答旅游知识类问题时应优先调用本工具, 结果不足时再用 webSearch.
 * 工具通过 ToolRegister 按分组注册, 开关由 mcp.tool-groups.rag.tools.knowledge-search 控制.
 */
@Slf4j
@Component
public class KnowledgeSearchTool {

    private final RagRetrievalService retrievalService;

    /**
     * 构造注入检索服务.
     *
     * @param retrievalService RAG 检索服务
     */
    public KnowledgeSearchTool(RagRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /**
     * 知识库语义检索.
     *
     * <p>返回 JSON: {results: [{content, docTitle, destination, theme, score, docId}], degraded, hint}.
     * degraded=rag_unavailable 或 results 为空时, 应转用 webSearch 获取外部信息.
     *
     * @param query       检索问题, 如 "北京秋季适合亲子的一日游路线"
     * @param destination 目的地过滤, 如 "北京"; 不确定时省略
     * @param topK        返回条数, 默认 5
     * @return JSON 结果文本
     */
    @Tool(description = "旅游知识库检索 — 从自有知识库(景点/美食/行程经验/历史规划报告)中语义检索, "
            + "回答旅游知识类问题时优先于 webSearch 使用; 返回 JSON, results 为空或 degraded=rag_unavailable 时转用 webSearch")
    public String knowledgeSearch(
            @ToolParam(required = true, description = "检索问题, 例如: 北京秋季适合亲子的一日游路线") String query,
            @ToolParam(required = false, description = "目的地过滤, 例如: 北京; 不确定时省略") String destination,
            @ToolParam(required = false, description = "返回条数, 默认 5") Integer topK) {
        log.info("知识库检索工具调用: query={}, destination={}, topK={}", query, destination, topK);
        return retrievalService.retrieve(query, destination, topK);
    }
}
