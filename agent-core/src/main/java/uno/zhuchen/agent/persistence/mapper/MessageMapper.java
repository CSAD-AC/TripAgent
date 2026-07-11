package uno.zhuchen.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import uno.zhuchen.agent.domain.entity.MessageEntity;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    /**
     * 按 conversationId 查询消息（按序号排序，复合索引覆盖过滤+排序）。
     */
    default List<MessageEntity> listByConversation(String conversationId) {
        return selectList(new QueryWrapper<MessageEntity>()
                .eq("conversation_id", conversationId)
                .orderByAsc("sequence_num"));
    }

    /**
     * 批量插入消息（替代逐条 insert，减少网络往返）。
     */
    @Insert("<script>"
        + "INSERT INTO message (conversation_id, role, content, model, token_count, trace_id, sequence_num, created_at) VALUES "
        + "<foreach collection='list' item='e' separator=','>"
        + "(#{e.conversationId}, #{e.role}, #{e.content}, #{e.model}, #{e.tokenCount}, #{e.traceId}, #{e.sequenceNum}, #{e.createdAt})"
        + "</foreach>"
        + "</script>")
    int insertBatch(@Param("list") List<MessageEntity> entities);
}
