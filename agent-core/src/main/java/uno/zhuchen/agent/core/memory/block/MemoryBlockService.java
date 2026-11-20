package uno.zhuchen.agent.core.memory.block;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uno.zhuchen.agent.domain.entity.MemoryBlockEntity;
import uno.zhuchen.agent.domain.entity.MemoryBlockRevisionEntity;
import uno.zhuchen.agent.domain.entity.MemoryRawEventEntity;
import uno.zhuchen.agent.domain.entity.MemoryUserPromptEntity;
import uno.zhuchen.agent.persistence.mapper.MemoryBlockMapper;
import uno.zhuchen.agent.persistence.mapper.MemoryBlockRevisionMapper;
import uno.zhuchen.agent.persistence.mapper.MemoryRawEventMapper;
import uno.zhuchen.agent.persistence.mapper.MemoryUserPromptMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class MemoryBlockService {

    public static final String USER_PROMPT = "USER_PROMPT";

    private final MemoryBlockMapper blockMapper;
    private final MemoryUserPromptMapper promptMapper;
    private final MemoryRawEventMapper rawEventMapper;
    private final MemoryBlockRevisionMapper revisionMapper;
    private final MemoryCandidateExtractor candidateExtractor;
    private final MemoryBlockUpdater blockUpdater;
    private final ObjectMapper objectMapper;
    private final int userPromptMaxChars;
    private final int profileMaxChars;
    private final int eventMaxChars;
    private final int maxAttempts;

    public MemoryBlockService(MemoryBlockMapper blockMapper,
                              MemoryUserPromptMapper promptMapper,
                              MemoryRawEventMapper rawEventMapper,
                              MemoryBlockRevisionMapper revisionMapper,
                              MemoryCandidateExtractor candidateExtractor,
                              MemoryBlockUpdater blockUpdater,
                              ObjectMapper objectMapper,
                              @Value("${app.memory.blocks.user-prompt-max-chars:600}") int userPromptMaxChars,
                              @Value("${app.memory.blocks.profile-max-chars:600}") int profileMaxChars,
                              @Value("${app.memory.blocks.event-max-chars:1000}") int eventMaxChars,
                              @Value("${app.memory.blocks.max-attempts:3}") int maxAttempts) {
        this.blockMapper = blockMapper;
        this.promptMapper = promptMapper;
        this.rawEventMapper = rawEventMapper;
        this.revisionMapper = revisionMapper;
        this.candidateExtractor = candidateExtractor;
        this.blockUpdater = blockUpdater;
        this.objectMapper = objectMapper;
        this.userPromptMaxChars = Math.max(1, userPromptMaxChars);
        this.profileMaxChars = Math.max(1, profileMaxChars);
        this.eventMaxChars = Math.max(1, eventMaxChars);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public MemoryUserPromptEntity prompt(String userId) {
        MemoryUserPromptEntity entity = promptMapper.selectById(userId);
        if (entity != null) {
            return entity;
        }
        LocalDateTime now = LocalDateTime.now();
        entity = new MemoryUserPromptEntity();
        entity.setUserId(userId);
        entity.setContent("");
        entity.setCharCount(0);
        entity.setVersion(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    public String userPrompt(String userId) {
        return prompt(userId).getContent();
    }

    public void saveUserPrompt(String userId, String content) {
        String next = content == null ? "" : content;
        if (next.length() > userPromptMaxChars) {
            throw new IllegalArgumentException(
                    "用户固定提示词超过上限 " + userPromptMaxChars + " 字");
        }
        MemoryUserPromptEntity old = promptMapper.selectById(userId);
        LocalDateTime now = LocalDateTime.now();
        String oldContent = old == null ? "" : old.getContent();
        if (old == null) {
            MemoryUserPromptEntity entity = new MemoryUserPromptEntity();
            entity.setUserId(userId);
            entity.setContent(next);
            entity.setCharCount(next.length());
            entity.setVersion(1);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            promptMapper.insert(entity);
        } else {
            old.setContent(next);
            old.setCharCount(next.length());
            old.setVersion(safeInt(old.getVersion()) + 1);
            old.setUpdatedAt(now);
            promptMapper.updateById(old);
        }
        writeRevision(userId, USER_PROMPT, oldContent, next, "MANUAL");
    }

    public List<MemoryBlockEntity> blocks(String userId) {
        List<MemoryBlockEntity> result = new ArrayList<>();
        for (MemoryBlockType type : MemoryBlockType.values()) {
            result.add(block(userId, type));
        }
        return result;
    }

    public MemoryBlockEntity block(String userId, MemoryBlockType type) {
        MemoryBlockEntity entity = findBlock(userId, type);
        if (entity != null) {
            return entity;
        }
        LocalDateTime now = LocalDateTime.now();
        entity = new MemoryBlockEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId);
        entity.setBlockType(type.name());
        entity.setContent("");
        entity.setCharCount(0);
        entity.setVersion(0);
        entity.setLastChangeSource("INIT");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    public String content(String userId, MemoryBlockType type) {
        MemoryBlockEntity entity = findBlock(userId, type);
        return entity == null || entity.getContent() == null ? "" : entity.getContent();
    }

    public void saveGeneratedBlock(String userId, MemoryBlockType type, String content) {
        String next = content == null ? "" : content.trim();
        int maxChars = maxChars(type);
        if (next.length() > maxChars) {
            throw new IllegalArgumentException(
                    type.name().toLowerCase() + " 超过上限 " + maxChars + " 字");
        }
        MemoryBlockEntity old = findBlock(userId, type);
        applyGeneratedBlock(userId, type, next, "MANUAL", true, old);
    }

    public List<MemoryBlockRevisionEntity> revisions(String userId, String blockType, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return revisionMapper.selectList(new QueryWrapper<MemoryBlockRevisionEntity>()
                .eq("user_id", userId)
                .eq("block_type", blockType)
                .orderByDesc("created_at")
                .last("LIMIT " + safeLimit));
    }

    public void rollback(String userId, String blockType, String revisionId) {
        MemoryBlockRevisionEntity revision = revisionMapper.selectById(revisionId);
        if (revision == null || !userId.equals(revision.getUserId())
                || !blockType.equals(revision.getBlockType())) {
            throw new IllegalArgumentException("修订记录不存在或不属于当前记忆块");
        }
        if (USER_PROMPT.equals(blockType)) {
            saveUserPrompt(userId, revision.getNewContent());
            return;
        }
        saveGeneratedBlock(userId, MemoryBlockType.fromPath(blockType), revision.getNewContent());
    }

    public void processPending(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<MemoryRawEventEntity> pending = rawEventMapper.selectList(
                new QueryWrapper<MemoryRawEventEntity>()
                        .eq("event_status", "PENDING")
                        .lt("attempt_count", maxAttempts)
                        .orderByAsc("created_at")
                        .last("LIMIT " + safeLimit));
        if (pending.isEmpty()) {
            return;
        }

        Map<String, List<MemoryRawEventEntity>> byUser = new LinkedHashMap<>();
        for (MemoryRawEventEntity event : pending) {
            byUser.computeIfAbsent(event.getUserId(), ignored -> new ArrayList<>()).add(event);
        }
        byUser.forEach(this::processUserEvents);
    }

    public int userPromptMaxChars() {
        return userPromptMaxChars;
    }

    public int maxChars(MemoryBlockType type) {
        return type == MemoryBlockType.PROFILE ? profileMaxChars : eventMaxChars;
    }

    public MemoryRawEventEntity latestEvent(String userId, String conversationId,
                                            LocalDateTime since) {
        return rawEventMapper.selectOne(new QueryWrapper<MemoryRawEventEntity>()
                .eq("user_id", userId)
                .eq(conversationId != null && !conversationId.isBlank(),
                        "conversation_id", conversationId)
                .ge(since != null, "created_at", since)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
    }

    public List<MemoryRawEventEntity> conversationEvents(String userId, String conversationId,
                                                         LocalDateTime since, int limit) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return rawEventMapper.selectList(new QueryWrapper<MemoryRawEventEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .eq("source", "KEYWORD")
                .ge(since != null, "created_at", since)
                .orderByDesc("created_at")
                .last("LIMIT " + safeLimit));
    }

    private void processUserEvents(String userId, List<MemoryRawEventEntity> events) {
        List<String> ids = events.stream().map(MemoryRawEventEntity::getId).toList();
        int claimed = rawEventMapper.update(null, new UpdateWrapper<MemoryRawEventEntity>()
                .in("id", ids)
                .eq("event_status", "PENDING")
                .set("event_status", "PROCESSING"));
        if (claimed == 0) {
            return;
        }

        List<MemoryRawEventEntity> claimedEvents = rawEventMapper.selectList(
                new QueryWrapper<MemoryRawEventEntity>()
                        .in("id", ids)
                        .eq("event_status", "PROCESSING")
                        .orderByAsc("created_at"));
        if (claimedEvents.isEmpty()) {
            return;
        }

        String candidateJson = null;
        try {
            MemoryCandidateResult candidates = candidateExtractor.extract(
                    claimedEvents.stream().map(MemoryRawEventEntity::getContent).toList());
            candidateJson = toJson(candidates);
            if (candidates.isEmpty()) {
                markDone(ids, "NO_CHANGE", candidateJson,
                        toJson(Map.of("changed", false, "reason", "no_candidates")));
                return;
            }

            MemoryBlockEntity profile = findBlock(userId, MemoryBlockType.PROFILE);
            MemoryBlockEntity eventBlock = findBlock(userId, MemoryBlockType.EVENT);
            boolean generatedEdited = (profile != null && profile.getGeneratedEditedAt() != null)
                    || (eventBlock != null && eventBlock.getGeneratedEditedAt() != null);
            String changeSource = resolveChangeSource(claimedEvents);
            MemoryBlockUpdateResult updated = blockUpdater.update(
                    profile == null ? "" : profile.getContent(),
                    eventBlock == null ? "" : eventBlock.getContent(),
                    candidates,
                    generatedEdited,
                    changeSource);

            String oldProfile = profile == null ? "" : profile.getContent();
            String oldEvents = eventBlock == null ? "" : eventBlock.getContent();
            boolean changed = !normalizeContent(oldProfile).equals(normalizeContent(updated.profile()))
                    || !normalizeContent(oldEvents).equals(normalizeContent(updated.events()));
            applyGeneratedBlock(userId, MemoryBlockType.PROFILE,
                    updated.profile(), changeSource, false, profile);
            applyGeneratedBlock(userId, MemoryBlockType.EVENT,
                    updated.events(), changeSource, false, eventBlock);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("changed", changed);
            result.put("profile", updated.profile());
            result.put("events", updated.events());
            result.put("generatedEditPreserved", updated.generatedEditPreserved());
            markDone(ids, changed ? "UPDATED" : "NO_CHANGE", candidateJson, toJson(result));
        } catch (Exception e) {
            log.warn("[memory-block] 事件处理失败: userId={}, events={}, error={}",
                    userId, claimedEvents.size(), e.getMessage());
            markFailure(ids, e.getMessage(), candidateJson);
        }
    }

    private void applyGeneratedBlock(String userId, MemoryBlockType type, String content,
                                     String changeSource, boolean userEdit,
                                     MemoryBlockEntity existing) {
        String next = content == null ? "" : content.trim();
        String oldContent = existing == null || existing.getContent() == null
                ? "" : existing.getContent();
        if (oldContent.equals(next) && existing != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            MemoryBlockEntity entity = new MemoryBlockEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setUserId(userId);
            entity.setBlockType(type.name());
            entity.setContent(next);
            entity.setCharCount(next.length());
            entity.setVersion(1);
            entity.setGeneratedEditedAt(userEdit ? now : null);
            entity.setLastChangeSource(changeSource);
            entity.setLastChangedAt(now);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            blockMapper.insert(entity);
        } else {
            existing.setContent(next);
            existing.setCharCount(next.length());
            existing.setVersion(safeInt(existing.getVersion()) + 1);
            existing.setLastChangeSource(changeSource);
            existing.setLastChangedAt(now);
            existing.setUpdatedAt(now);
            if (userEdit) {
                existing.setGeneratedEditedAt(now);
            }
            blockMapper.updateById(existing);
        }
        writeRevision(userId, type.name(), oldContent, next, changeSource);
    }

    private MemoryBlockEntity findBlock(String userId, MemoryBlockType type) {
        return blockMapper.selectOne(new QueryWrapper<MemoryBlockEntity>()
                .eq("user_id", userId)
                .eq("block_type", type.name())
                .last("LIMIT 1"));
    }

    private void markDone(List<String> ids, String resultStatus,
                          String candidateJson, String resultJson) {
        rawEventMapper.update(null, new UpdateWrapper<MemoryRawEventEntity>()
                .in("id", ids)
                .eq("event_status", "PROCESSING")
                .set("event_status", "DONE")
                .set("result_status", resultStatus)
                .set("candidate_json", candidateJson)
                .set("result_json", resultJson)
                .set("error_message", null)
                .set("processed_at", LocalDateTime.now()));
    }

    private void markFailure(List<String> ids, String errorMessage, String candidateJson) {
        rawEventMapper.update(null, new UpdateWrapper<MemoryRawEventEntity>()
                .in("id", ids)
                .eq("event_status", "PROCESSING")
                .setSql("event_status = CASE WHEN attempt_count + 1 >= "
                        + maxAttempts + " THEN 'FAILED' ELSE 'PENDING' END")
                .setSql("attempt_count = attempt_count + 1")
                .set("result_status", "FAILED")
                .set("candidate_json", candidateJson)
                .set("error_message", truncate(errorMessage, 500))
                .set("processed_at", LocalDateTime.now()));
    }

    private String resolveChangeSource(List<MemoryRawEventEntity> events) {
        if (events.stream().anyMatch(e -> "KEYWORD".equals(e.getSource()))) {
            return "KEYWORD";
        }
        if (events.stream().anyMatch(e -> "DAILY".equals(e.getSource()))) {
            return "DAILY";
        }
        return "AUTO_CONSOLIDATION";
    }

    private void writeRevision(String userId, String blockType,
                               String oldContent, String newContent, String source) {
        MemoryBlockRevisionEntity revision = new MemoryBlockRevisionEntity();
        revision.setId(UUID.randomUUID().toString());
        revision.setUserId(userId);
        revision.setBlockType(blockType);
        revision.setOldContent(oldContent);
        revision.setNewContent(newContent);
        revision.setChangeSource(source);
        revision.setCreatedAt(LocalDateTime.now());
        revisionMapper.insert(revision);
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static String normalizeContent(String content) {
        return content == null ? "" : content.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[memory-block] 审计 JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
