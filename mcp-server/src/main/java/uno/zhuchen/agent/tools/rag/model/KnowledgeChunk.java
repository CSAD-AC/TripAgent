package uno.zhuchen.agent.tools.rag.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * RAG 知识分块实体 — 对应表 knowledge_chunk.
 *
 * <p>检索的最小单位, 主键 id 与 Milvus collection 的向量主键一致;
 * MySQL 为源数据, Milvus 为可重建索引.
 */
@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    /** 分块主键 (ASSIGN_UUID, 与 Milvus 向量主键一致) */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属文档 ID */
    private String docId;

    /** 文档内分块序号 */
    private Integer seq;

    /** 分块原文 */
    private String content;

    /** 字符数 */
    private Integer charCount;

    /** 目的地 (metadata, 过滤检索用, 如 北京) */
    private String destination;

    /** 主题 (metadata, 过滤检索用, 如 景点 / 美食 / 行程) */
    private String theme;

    /** 向量化状态: pending / indexed / failed */
    private String embeddingStatus;
}
