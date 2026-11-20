package uno.zhuchen.agent.persistence.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.zhuchen.agent.common.exception.NotFoundException;
import uno.zhuchen.agent.domain.entity.ConversationEntity;
import uno.zhuchen.agent.persistence.cache.RedisMessageCache;
import uno.zhuchen.agent.persistence.mapper.ConversationMapper;
import uno.zhuchen.agent.persistence.mapper.MessageMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationIsolationTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private RedisMessageCache messageCache;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationMapper, messageMapper, messageCache);
    }

    @Test
    void listUsesAuthenticatedUserId() {
        when(conversationMapper.listByUserId(
                org.mockito.ArgumentMatchers.eq("user-a"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new Page<>());

        service.listConversations("user-a", 0, 20);

        verify(conversationMapper).listByUserId(
                org.mockito.ArgumentMatchers.eq("user-a"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void foreignConversationIsReportedAsNotFound() {
        ConversationEntity conversation = conversation("user-b");
        when(conversationMapper.selectById("c1")).thenReturn(conversation);

        assertThrows(NotFoundException.class,
                () -> service.getConversation("user-a", "c1"));
    }

    @Test
    void ownerCanRenameConversation() {
        ConversationEntity conversation = conversation("user-a");
        when(conversationMapper.selectById("c1")).thenReturn(conversation);

        service.updateConversation("user-a", "c1", "新标题");

        verify(conversationMapper).updateTitle("c1", "新标题");
    }

    @Test
    void foreignConversationCannotBeDeleted() {
        ConversationEntity conversation = conversation("user-b");
        when(conversationMapper.selectById("c1")).thenReturn(conversation);

        assertThrows(NotFoundException.class,
                () -> service.deleteConversation("user-a", "c1"));
    }

    @Test
    void ownerCanReadDetails() {
        ConversationEntity conversation = conversation("user-a");
        conversation.setTitle("成都");
        when(conversationMapper.selectById("c1")).thenReturn(conversation);
        when(messageMapper.listByConversation("c1")).thenReturn(java.util.List.of());

        var detail = service.getConversation("user-a", "c1");

        assertEquals("成都", detail.getTitle());
    }

    private static ConversationEntity conversation(String userId) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("c1");
        conversation.setUserId(userId);
        conversation.setStatus("active");
        conversation.setMessageCount(0);
        return conversation;
    }
}
