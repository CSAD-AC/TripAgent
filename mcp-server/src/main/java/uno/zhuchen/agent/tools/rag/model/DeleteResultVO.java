package uno.zhuchen.agent.tools.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识文档删除结果视图对象 — 替代原先 {success:true} 手拼 Map 的弱类型出参.
 *
 * <p>收敛 {@code KnowledgeDocController.delete} 的返回, 序列化 key 保持 success 与前端契约一致.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeleteResultVO {

    /** 是否删除成功 */
    private Boolean success;
}