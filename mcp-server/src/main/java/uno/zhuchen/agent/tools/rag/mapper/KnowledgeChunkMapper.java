package uno.zhuchen.agent.tools.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeChunk;

/**
 * 知识分块表 Mapper — 提供分块 CRUD 与按文档/向量化状态查询能力.
 *
 * <p>继承 MyBatis-Plus BaseMapper 即可获得通用 CRUD,
 * 由 MybatisPlusAutoConfiguration 自动扫描注册.
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {
}
