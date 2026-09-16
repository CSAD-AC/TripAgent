package uno.zhuchen.agent.tools.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunk;

import java.util.List;

/**
 * 知识分块表 Mapper — 提供分块 CRUD 与按文档/向量化状态查询能力.
 *
 * <p>继承 MyBatis-Plus BaseMapper 即可获得通用 CRUD;
 * 另提供批量插入与批量置 indexed 两个自定义方法, 供入库管线一次往返写整批分块,
 * 避免大文档数百条分块逐条 CRUD 造成的数据库往返放大.
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 批量插入分块(调用方需已为每条预分配主键, 与 Milvus 向量主键一致).
     *
     * @param chunks 待插入分块列表(字段须已齐全)
     * @return 影响行数
     */
    @Insert("<script>" +
            "INSERT INTO knowledge_chunk (id, doc_id, seq, content, char_count, destination, theme, embedding_status) " +
            "VALUES " +
            "<foreach collection='chunks' item='c' separator=','>" +
            "(#{c.id}, #{c.docId}, #{c.seq}, #{c.content}, #{c.charCount}, #{c.destination}, #{c.theme}, #{c.embeddingStatus})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("chunks") List<KnowledgeChunk> chunks);

    /**
     * 批量更新分块为已向量化(indexed), 一次往返完成整批状态回写.
     *
     * @param ids 分块主键列表
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE knowledge_chunk SET embedding_status = 'indexed' WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int batchMarkEmbedded(@Param("ids") List<String> ids);
}
