package uno.zhuchen.agent.core.memory.block;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.workflow.util.JsonExtractor;

import java.util.ArrayList;
import java.util.List;

@Component("longTermMemoryCandidateExtractor")
public class MemoryCandidateExtractor {

    private static final String SYSTEM_PROMPT = """
            你是长期记忆抽取器。输入是一组用户消息。
            只抽取“全局、稳定、长期有效”的事实，输出严格 JSON：
            {"profileCandidates":[],"eventCandidates":[],"discarded":[]}

            规则：
            1. profileCandidates 只放长期稳定信息：常住城市、长期饮食偏好、长期同行人、长期预算习惯
            2. eventCandidates 只放已经发生或明确计划的具体经历，每条一行
            3. discarded 放本次行程、临时状态、工具查询、寒暄、重复内容、敏感信息
            4. 禁止新增原文没有的事实
            5. 不要输出解释，不要用代码块包裹
            """;

    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final JsonExtractor jsonExtractor;

    public MemoryCandidateExtractor(ModelRouter modelRouter, ObjectMapper objectMapper) {
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
        this.jsonExtractor = new JsonExtractor(objectMapper);
    }

    public MemoryCandidateResult extract(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return new MemoryCandidateResult(List.of(), List.of(), List.of());
        }
        String userContent = String.join("\n---\n", messages);
        List<Message> request = List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userContent)
        );
        String response = modelRouter.route(null)
                .call(request, modelRouter.resolveModelName(null))
                .getText();
        JsonNode root = jsonExtractor.extract(response);
        if (root == null) {
            throw new IllegalStateException("长期记忆抽取器未返回有效 JSON");
        }
        return new MemoryCandidateResult(
                readArray(root, "profileCandidates"),
                readArray(root, "eventCandidates"),
                readArray(root, "discarded"));
    }

    private List<String> readArray(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }
}
