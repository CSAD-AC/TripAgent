package uno.zhuchen.agent.core.multi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.tool.ToolRegistry;

/**
 * 知识检索子代理 — 联网搜索资料、对比分析、查证实时信息
 *
 * <p>预设 SubAgent 示例: 内部工具子集为 cloud 组(webSearch + pageFetch),
 * 回答时效性问题/对比分析时由 Manager 调度.
 */
@Component
public class KnowledgeSubAgent extends SubAgentBase {

    public KnowledgeSubAgent(@Lazy ModelRouter modelRouter, ToolRegistry toolRegistry,
                             ObjectMapper objectMapper) {
        super("knowledgeExpert",
                "知识检索专家子代理 — 联网搜索资料、对比分析、查证实时信息, 引用来源",
                MultiAgentPrompts.KNOWLEDGE_SUBAGENT_SYSTEM_PROMPT,
                modelRouter, toolRegistry, objectMapper, 4);
    }

    @Override
    protected String[] ownToolNames() {
        return new String[]{"webSearch", "pageFetch"};
    }
}
