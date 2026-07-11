package uno.zhuchen.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import uno.zhuchen.agent.domain.entity.ConversationEntity;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /**
     * 用户对话列表（分页）。
     */
    @Select("SELECT * FROM conversation WHERE user_id=#{userId} AND status='active' ORDER BY updated_at DESC")
    IPage<ConversationEntity> listByUserId(@Param("userId") String userId, Page<?> page);

    /**
     * 软删除对话。
     */
    @Update("UPDATE conversation SET status='deleted' WHERE id=#{id}")
    int softDeleteById(@Param("id") String id);

    /**
     * 原子自增消息计数。
     */
    @Update("UPDATE conversation SET message_count = message_count + #{delta} WHERE id=#{conversationId}")
    int incrementMessageCount(@Param("conversationId") String conversationId, @Param("delta") int delta);
}
