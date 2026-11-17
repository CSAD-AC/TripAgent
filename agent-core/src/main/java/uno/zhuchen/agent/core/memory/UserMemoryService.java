package uno.zhuchen.agent.core.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.common.exception.NotFoundException;
import uno.zhuchen.agent.domain.entity.UserMemoryEntity;
import uno.zhuchen.agent.domain.vo.MemoryLabels;
import uno.zhuchen.agent.domain.vo.UserMemoryVO;
import uno.zhuchen.agent.persistence.mapper.UserMemoryMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class UserMemoryService {

    private final UserMemoryMapper mapper;
    private final MemoryExtractor extractor;
    private final int maxItems;
    private final int maxValueChars;

    public UserMemoryService(UserMemoryMapper mapper,
                             MemoryExtractor extractor,
                             @Value("${app.memory.long-term.max-items:10}") int maxItems,
                             @Value("${app.memory.long-term.max-value-chars:120}") int maxValueChars) {
        this.mapper = mapper;
        this.extractor = extractor;
        this.maxItems = Math.max(1, maxItems);
        this.maxValueChars = Math.max(20, maxValueChars);
    }

    public void extractAndStore(String userId, String conversationId, String userMessage) {
        if (userId == null || userId.isBlank() || userMessage == null || userMessage.isBlank()) {
            return;
        }
        try {
            for (MemoryCandidate candidate : extractor.extract(userMessage)) {
                upsert(userId, conversationId, candidate);
            }
        } catch (Exception e) {
            log.warn("[long-term-memory] 提取或保存失败: userId={}, conversationId={}, error={}",
                    userId, conversationId, e.getMessage());
        }
    }

    public MemorySummary loadSummary(String userId) {
        if (userId == null || userId.isBlank()) {
            return new MemorySummary(List.of());
        }
        try {
            List<UserMemoryEntity> entities = activeMemories(userId);
            List<MemorySummary.MemoryLine> lines = entities.stream()
                    .limit(maxItems)
                    .map(entity -> new MemorySummary.MemoryLine(
                            MemoryLabels.labelFor(entity.getMemoryKey()),
                            normalizeDisplayValue(entity)))
                    .toList();
            return new MemorySummary(lines);
        } catch (Exception e) {
            log.warn("[long-term-memory] 读取失败: userId={}, error={}", userId, e.getMessage());
            return new MemorySummary(List.of());
        }
    }

    public List<UserMemoryVO> list(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            return activeMemories(userId).stream().map(UserMemoryVO::from).toList();
        } catch (Exception e) {
            log.warn("[long-term-memory] 列表查询失败: userId={}, error={}", userId, e.getMessage());
            return List.of();
        }
    }

    public void delete(String userId, String memoryId) {
        UserMemoryEntity entity = requireOwned(userId, memoryId);
        mapper.update(null, new UpdateWrapper<UserMemoryEntity>()
                .eq("id", entity.getId())
                .set("status", "deleted")
                .set("updated_at", LocalDateTime.now()));
    }

    public void deleteAll(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        mapper.update(null, new UpdateWrapper<UserMemoryEntity>()
                .eq("user_id", userId)
                .eq("status", "active")
                .set("status", "deleted")
                .set("updated_at", LocalDateTime.now()));
    }

    private List<UserMemoryEntity> activeMemories(String userId) {
        return mapper.selectList(new QueryWrapper<UserMemoryEntity>()
                .eq("user_id", userId)
                .eq("status", "active")
                .orderByDesc("updated_at"));
    }

    private UserMemoryEntity requireOwned(String userId, String memoryId) {
        if (userId == null || userId.isBlank() || memoryId == null || memoryId.isBlank()) {
            throw new NotFoundException("记忆不存在");
        }
        UserMemoryEntity entity = mapper.selectById(memoryId);
        if (entity == null
                || !Objects.equals(userId, entity.getUserId())
                || !"active".equals(entity.getMemoryStatus())) {
            throw new NotFoundException("记忆不存在");
        }
        return entity;
    }

    private void upsert(String userId, String conversationId, MemoryCandidate candidate) {
        String value = normalizeStoredValue(candidate.value());
        if (value.isBlank()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        UserMemoryEntity existing = mapper.selectOne(new QueryWrapper<UserMemoryEntity>()
                .eq("user_id", userId)
                .eq("memory_key", candidate.key())
                .last("LIMIT 1"));

        if (existing == null) {
            UserMemoryEntity entity = new UserMemoryEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setUserId(userId);
            entity.setMemoryType(candidate.type());
            entity.setMemoryKey(candidate.key());
            entity.setMemoryValue(value);
            entity.setSourceConversationId(conversationId);
            entity.setConfidence(new BigDecimal("0.800"));
            entity.setMemoryStatus("active");
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);
            return;
        }

        mapper.update(null, new UpdateWrapper<UserMemoryEntity>()
                .eq("id", existing.getId())
                .set("memory_value", value)
                .set("source_conversation_id", conversationId)
                .set("status", "active")
                .set("updated_at", now));
    }

    private String normalizeStoredValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= maxValueChars
                ? normalized
                : normalized.substring(0, maxValueChars);
    }

    private static String normalizeDisplayValue(UserMemoryEntity entity) {
        String key = entity.getMemoryKey();
        String value = entity.getMemoryValue();
        if (key != null && key.startsWith("travel.preference.") && "true".equals(value)) {
            String label = MemoryLabels.labelFor(key);
            int separator = label.indexOf('：');
            return separator >= 0 ? label.substring(separator + 1) : label;
        }
        return value;
    }
}
