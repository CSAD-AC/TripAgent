package uno.zhuchen.agent.persistence.query;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import uno.zhuchen.agent.domain.entity.MessageEntity;

import java.util.List;

@Mapper
public interface MessageQuery {

    @Select("""
            SELECT DISTINCT c.user_id
            FROM conversation c
            JOIN message m ON m.conversation_id = c.id
            WHERE m.role = 'user'
              AND c.user_id IS NOT NULL
              AND c.user_id <> 'anonymous'
            """)
    List<String> activeUserIds();

    @Select("""
            SELECT m.*
            FROM message m
            JOIN conversation c ON c.id = m.conversation_id
            WHERE c.user_id = #{userId}
              AND m.role = 'user'
              AND (#{lastMessageId} IS NULL OR m.id > #{lastMessageId})
            ORDER BY m.id ASC
            LIMIT #{limit}
            """)
    List<MessageEntity> newUserMessagesAfter(@Param("userId") String userId,
                                             @Param("lastMessageId") Long lastMessageId,
                                             @Param("limit") int limit);
}
