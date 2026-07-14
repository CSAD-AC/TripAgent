package uno.zhuchen.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import uno.zhuchen.agent.domain.entity.MessageEntity;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    /**
     * 查询某对话当前最大消息序号。
     */
    @Select("SELECT COALESCE(MAX(sequence_num), -1) FROM message WHERE conversation_id=#{conversationId}")
    int selectMaxSequenceNum(@Param("conversationId") String conversationId);

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
        + "INSERT IGNORE INTO message (conversation_id, role, content, metadata, model, token_count, trace_id, sequence_num, created_at) VALUES "
        + "<foreach collection='list' item='e' separator=','>"
        + "(#{e.conversationId}, #{e.role}, #{e.content}, #{e.metadata}, #{e.model}, #{e.tokenCount}, #{e.traceId}, #{e.sequenceNum}, #{e.createdAt})"
        + "</foreach>"
        + "</script>")
    int insertBatch(@Param("list") List<MessageEntity> entities);
}
