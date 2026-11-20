package uno.zhuchen.agent.core.memory.block;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.workflow.util.JsonExtractor;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MemoryBlockUpdater {

    private static final String SYSTEM_PROMPT = """
            你是长期记忆写入器。输入是当前两块记忆和本次最新候选事实。
            请基于全文重写最终记忆，而不是追加，输出严格 JSON：
            {"profile":"...","events":"..."}

            规则：
            1. profileCandidates 和 eventCandidates 是本次最新事实，优先级最高
            2. 输出完整最终记忆，不要把旧记忆原样拼接后再追加候选
            3. 候选与旧内容表达同一属性但值不同时，删除旧值，只保留最新候选值；
               同一属性不得同时出现新旧两个值
            4. 同一批候选中若有冲突，以列表中靠后的候选为准
            5. 只能写入旧记忆或候选事实中存在的内容，禁止自行新增事实
            6. generatedEdited=true 时保留与最新候选不冲突的用户编辑；
               与最新候选冲突的旧编辑必须被替换
            7. profile 不超过 600 字，events 不超过 1000 字
            8. events 每条一行，行尾保留日期
            9. 不要输出解释，不要用代码块包裹
            """;

    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final JsonExtractor jsonExtractor;

    public MemoryBlockUpdater(ModelRouter modelRouter, ObjectMapper objectMapper) {
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
        this.jsonExtractor = new JsonExtractor(objectMapper);
    }

    public MemoryBlockUpdateResult update(String currentProfile,
                                          String currentEvents,
                                          MemoryCandidateResult candidates,
                                          boolean generatedEdited,
                                          String changeSource) {
        String oldProfile = normalize(currentProfile);
        String oldEvents = normalize(currentEvents);
        if (candidates == null || candidates.isEmpty()) {
            return new MemoryBlockUpdateResult(oldProfile, oldEvents, true);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentProfile", oldProfile);
        payload.put("currentEvents", oldEvents);
        payload.put("profileCandidates", candidates.profileCandidates());
        payload.put("eventCandidates", candidates.eventCandidates());
        payload.put("generatedEdited", generatedEdited);
        payload.put("changeSource", changeSource);

        try {
            String userContent = objectMapper.writeValueAsString(payload);
            List<Message> request = List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(userContent)
            );
            String response = modelRouter.route(null)
                    .call(request, modelRouter.resolveModelName(null))
                    .getText();
            JsonNode root = jsonExtractor.extract(response);
            if (root == null) {
                throw new IllegalStateException("长期记忆写入器未返回有效 JSON");
            }
            String modelProfile = text(root, "profile", oldProfile);
            String modelEvents = text(root, "events", oldEvents);
            String nextProfile = mergeSegments(
                    modelProfile, candidates.profileCandidates(), true);
            String nextEvents = mergeSegments(
                    modelEvents, candidates.eventCandidates(), false);

            boolean profilePreserved = generatedEditPreserved(oldProfile, nextProfile);
            boolean eventsPreserved = generatedEditPreserved(oldEvents, nextEvents);

            return new MemoryBlockUpdateResult(
                    fit(nextProfile, MemoryBlockType.PROFILE),
                    fit(nextEvents, MemoryBlockType.EVENT),
                    profilePreserved && eventsPreserved);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("长期记忆写入失败", e);
        }
    }

    static String fit(String content, MemoryBlockType type) {
        String text = normalize(content);
        return text.length() <= type.maxChars() ? text : text.substring(0, type.maxChars()).trim();
    }

    private static String text(JsonNode root, String field, String fallback) {
        JsonNode node = root.get(field);
        return node != null && node.isTextual() ? node.asText() : fallback;
    }

    private static String mergeSegments(String modelContent,
                                        List<String> candidates,
                                        boolean profile) {
        List<String> segments = new ArrayList<>();
        addSegments(segments, modelContent);

        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String normalizedCandidate = normalize(candidate);
            boolean exists = segments.stream()
                    .map(MemoryBlockUpdater::normalizeForCompare)
                    .anyMatch(segment -> segment.contains(normalizeForCompare(normalizedCandidate)));
            if (!exists) {
                segments.add(normalizedCandidate);
            }
        }
        String delimiter = profile ? "；" : "\n";
        return String.join(delimiter, segments);
    }

    private static boolean generatedEditPreserved(String oldContent, String nextContent) {
        List<String> oldSegments = segments(oldContent);
        if (oldSegments.isEmpty()) {
            return true;
        }
        List<String> nextSegments = segments(nextContent);
        for (String oldSegment : oldSegments) {
            String normalizedOld = normalizeForCompare(oldSegment);
            boolean preserved = nextSegments.stream()
                    .map(MemoryBlockUpdater::normalizeForCompare)
                    .anyMatch(segment -> segment.contains(normalizedOld));
            if (!preserved) {
                return false;
            }
        }
        return true;
    }

    private static void addSegments(List<String> target, String content) {
        for (String segment : segments(content)) {
            String normalized = normalize(segment);
            String comparable = normalizeForCompare(normalized);
            boolean exists = target.stream()
                    .map(MemoryBlockUpdater::normalizeForCompare)
                    .anyMatch(existing -> existing.contains(comparable) || comparable.contains(existing));
            if (!exists) {
                target.add(normalized);
            }
        }
    }

    private static List<String> segments(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : content.split("[;；\\n\\r]+")) {
            String normalized = normalize(part);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String normalizeForCompare(String value) {
        return normalize(value).replaceAll("[\\s；;，,。：:]+", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
