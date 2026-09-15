package uno.zhuchen.agent.tools.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import uno.zhuchen.agent.tools.rag.model.KnowledgeDoc;

/**
 * 知识文档表 Mapper — 提供文档 CRUD 与按状态/来源统计能力.
 *
 * <p>继承 MyBatis-Plus BaseMapper 即可获得通用 CRUD,
 * 由 MybatisPlusAutoConfiguration 自动扫描注册.
 */
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDoc> {
}
