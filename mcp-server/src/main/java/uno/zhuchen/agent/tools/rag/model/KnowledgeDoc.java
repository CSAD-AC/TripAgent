package uno.zhuchen.agent.tools.rag.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * RAG 知识文档实体 — 对应表 knowledge_doc.
 *
 * <p>一次入库的单位: 手工上传 / 自沉淀卡片 / 规划报告 / 种子数据.
 * 状态机: pending -> processing -> indexed / failed.
 * 时间列 created_at / updated_at 由数据库默认值与 ON UPDATE 维护.
 */
@Data
@TableName("knowledge_doc")
public class KnowledgeDoc {

    /** 文档主键 (ASSIGN_UUID, 与 Milvus 侧 docId metadata 对应) */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 文档标题 */
    private String title;

    /** 来源类型: manual / self_precip / graph_report / url / seed */
    private String sourceType;

    /** 溯源引用: traceId / conversationId / URL */
    private String sourceRef;

    /** 索引状态: pending / processing / indexed / failed */
    private String status;

    /** 分块数量 */
    private Integer chunkCount;

    /** 入库失败原因 (status=failed 时记录) */
    private String errorMsg;
}
