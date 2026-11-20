package uno.zhuchen.agent.core.memory.block;

import org.springframework.stereotype.Service;
import uno.zhuchen.agent.domain.entity.MemoryRawEventEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryRawEventMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class MemoryRawEventService {

    private static final int MAX_CONTENT_CHARS = 2000;

    private final MemoryRawEventMapper mapper;

    public MemoryRawEventService(MemoryRawEventMapper mapper) {
        this.mapper = mapper;
    }

    public String appendKeyword(String userId, String conversationId, String content) {
        String normalized = normalize(content);
        if (isBlank(userId) || normalized.isEmpty()) {
            return null;
        }
        return insert(userId, conversationId, null,
                eventKey(userId, conversationId, normalized, "KEYWORD"),
                truncate(normalized, MAX_CONTENT_CHARS), "KEYWORD");
    }

    public String appendDaily(String userId, String conversationId, Long messageId, String content) {
        String normalized = normalize(content);
        if (isBlank(userId) || messageId == null || normalized.isEmpty()) {
            return null;
        }
        return insert(userId, conversationId, messageId,
                "message:" + messageId,
                truncate(normalized, MAX_CONTENT_CHARS), "DAILY");
    }

    String eventKey(String userId, String conversationId, String content, String source) {
        String raw = normalize(userId) + "|" + normalize(conversationId) + "|"
                + normalize(source) + "|" + normalize(content);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String insert(String userId, String conversationId, Long messageId,
                          String eventKey, String content, String source) {
        MemoryRawEventEntity entity = new MemoryRawEventEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setConversationId(conversationId);
        entity.setMessageId(messageId);
        entity.setEventKey(eventKey);
        entity.setContent(content);
        entity.setSource(source);
        entity.setEventStatus("PENDING");
        entity.setAttemptCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        return mapper.insertIgnore(entity) > 0 ? entity.getId() : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
