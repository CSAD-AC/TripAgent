package uno.zhuchen.agent.tools.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识文档分页列表视图对象 — 替代原先 {total, items} 手拼 Map 的弱类型出参.
 *
 * <p>收敛 {@code KnowledgeDocController.list} 的返回, 序列化 key 保持 total / items
 * 与前端契约一致, 但获得编译期类型安全.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocPageVO {

    /** 文档总数(未删除) */
    private Long total;

    /** 当前页文档列表 */
    private List<KnowledgeDocVO> items;
}