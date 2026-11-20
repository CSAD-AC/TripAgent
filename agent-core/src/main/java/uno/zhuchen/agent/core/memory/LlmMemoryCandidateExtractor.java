package uno.zhuchen.agent.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import uno.zhuchen.agent.config.ModelRouter;
import uno.zhuchen.agent.core.llm.ChatModel;
import uno.zhuchen.workflow.util.JsonExtractor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LLM 深度记忆抽取器 — 用模型把用户消息归纳为结构化记忆候选。
 *
 * <p>与 {@link RuleMemoryCandidateExtractor} 的分工:
 * <ul>
 *   <li>规则抽取: 零成本、零延迟, 覆盖显式偏好("我喜欢吃辣")</li>
 *   <li>LLM 抽取: 覆盖隐含信息("这次带老人出行, 酒店最好在一楼")这类规则抓不到的表达</li>
 * </ul>
 *
 * <p>工程约束(评测/成本相关):
 * <ul>
 *   <li>默认关闭({@code app.memory.extraction.llm-enabled=false}), 打开后每轮对话多一次模型调用</li>
 *   <li>输出必须是固定 JSON; 解析失败/模型报错/超时一律回退规则抽取, 不让记忆链路中断</li>
 *   <li>只允许五种类型, 敏感信息(证件/密钥/银行卡)整段丢弃</li>
 *   <li>单次最多 {@code maxCandidates} 条, 单条 value 截断到 {@code maxValueChars}, 防止脏记忆膨胀</li>
 * </ul>
 */
@Slf4j
public class LlmMemoryCandidateExtractor implements MemoryCandidateExtractor {

    /** 允许的记忆类型, 与 {@link MemoryItemType} 同名 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "PROFILE", "PREFERENCE", "CONSTRAINT", "VETO", "EPISODIC");

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(api[ _-]?key|password|passwd|身份证|银行卡|cvv|验证码|密钥)");

    /** value 只写占位符等于没写(典型坏例: 偏好写成 "true"), 直接丢弃 */
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "true", "false", "yes", "no", "1", "0", "是", "否", "有", "无", "null", "none");

    private static final String SYSTEM_PROMPT = """
            你是旅行助手的长期记忆抽取器。从用户这句话里抽取值得长期记住的信息。

            只输出 JSON, 不要解释, 格式固定:
            {"memories":[{"type":"PREFERENCE","key":"travel.preference.food","value":"喜欢川菜",
            "confidence":0.9,"importance":0.6}]}

            字段规则:
            - type 只能是 PROFILE(用户信息: 常住城市/同行人)/PREFERENCE(偏好)/CONSTRAINT(规划约束: 预算上限、时间限制、无障碍要求)/VETO(明确要求不要记住或不要再提的内容)/EPISODIC(一次具体经历, 如"上次规划过京都 5 天行程")

            类型判定必须按下面顺序, 同一条信息只能归入一类:
            1) 用户明确要求"不要记住/不要再提" → VETO
            2) 硬性要求, 不满足就算方案失败(无障碍、一楼房间、禁烟、必须高铁) → CONSTRAINT
            3) 只在本次/这次/近期出行成立的信息(这次带老人, 本次预算 5000) → CONSTRAINT
            4) 长期稳定的个人属性(常住城市、出发城市、长期同行人构成) → PROFILE
            5) 长期稳定的喜好或厌恶(爱吃辣、不喜欢人多) → PREFERENCE
            6) 具体的一次经历(上次去过京都、上次住四条河原町) → EPISODIC

            去重要求(常见错误, 必须避免):
            - 同一件事不要同时输出 PROFILE 和 PREFERENCE, 例如"不吃辣"只输出一条 PREFERENCE
            - 不要输出包含关系的大小两条, 例如"不吃辣"与"喜欢美食但完全不吃辣"只能留一条更具体的
            - 一条记忆里已经包含的信息, 不要拆成第二条重复表达

            - key 用英文小写点号命名: profile.*, travel.preference.*, travel.constraint.*, veto.*, travel.episode.*
            - value 必须是能独立读懂的中文短语, 不超过 40 字, 不要包含句末标点
            - value 禁止写 true/false/是/否/数字等占位值, key 只是标识不能代替 value;
              例: key=travel.preference.food 时 value 应写成"喜欢美食, 尤其川菜"而不是"true"
            - value 里要带上主体信息: 时间、地点、金额、人数等具体事实优先保留
            - confidence 0~1, 用户明确陈述取 0.9 以上, 推断取 0.6 左右
            - importance 0~1, 影响后续规划取 0.6 以上
            - 没有值得记住的信息时输出 {"memories":[]}
            - 不要输出证件号、银行卡、密码、验证码、密钥等信息
            - 最多 5 条, 不要重复表达同一件事
            """;

    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final JsonExtractor jsonExtractor;
    private final MemoryCandidateExtractor fallback;
    private final String modelId;
    private final int maxCandidates;
    private final int maxValueChars;

    public LlmMemoryCandidateExtractor(ModelRouter modelRouter,
                                       ObjectMapper objectMapper,
                                       MemoryCandidateExtractor fallback,
                                       String modelId,
                                       int maxCandidates,
                                       int maxValueChars) {
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
        this.jsonExtractor = new JsonExtractor(objectMapper);
        this.fallback = fallback;
        this.modelId = modelId;
        this.maxCandidates = Math.max(1, maxCandidates);
        this.maxValueChars = Math.max(8, maxValueChars);
    }

    @Override
    public String version() {
        return "llm-v1";
    }

    @Override
    public List<MemoryExtractionResult> extract(String payloadJson) {
        String message = extractMessage(payloadJson);
        if (message == null || message.isBlank() || SENSITIVE.matcher(message).find()) {
            return List.of();
        }
        try {
            List<MemoryExtractionResult> results = callModel(message);
            if (!results.isEmpty()) {
                return results;
            }
            // 模型认为无信息时, 仍用规则兜底: 规则命中往往是硬信号(如"我喜欢吃辣")
            return fallback.extract(payloadJson);
        } catch (Exception e) {
            log.warn("[memory-llm] 抽取失败, 回退规则抽取: error={}", e.getMessage());
            return fallback.extract(payloadJson);
        }
    }

    private List<MemoryExtractionResult> callModel(String message) {
        List<Message> messages = List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(message));
        ChatModel model = modelRouter.route(modelId);
        AssistantMessage reply = model.call(messages, modelRouter.resolveModelName(modelId));
        String text = reply == null ? null : reply.getText();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        JsonNode root = jsonExtractor.extract(text);
        if (root == null) {
            throw new IllegalStateException("LLM 输出无法解析为 JSON");
        }
        JsonNode array = root.isArray() ? root : root.path("memories");
        if (!array.isArray()) {
            array = root.path("items");
        }
        if (!array.isArray()) {
            return List.of();
        }
        List<MemoryExtractionResult> results = new ArrayList<>();
        for (JsonNode node : array) {
            MemoryExtractionResult result = convert(node);
            if (result != null) {
                results.add(result);
            }
            if (results.size() >= maxCandidates) {
                break;
            }
        }
        return dedupe(results);
    }

    /**
     * 同一轮抽取内的去重。
     *
     * <p>模型经常把同一件事拆成大小两条("不吃辣" + "喜欢美食但完全不吃辣"),
     * 或者同一件事给两个类型(PROFILE + PREFERENCE)。入库前按"值包含关系 + 类型优先级"
     * 合并, 只保留信息量最大的一条。
     */
    private static List<MemoryExtractionResult> dedupe(List<MemoryExtractionResult> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        List<MemoryExtractionResult> kept = new ArrayList<>();
        for (MemoryExtractionResult candidate : candidates) {
            String value = normalizeValue(candidate.value());
            boolean dropNew = false;
            List<MemoryExtractionResult> survivors = new ArrayList<>();
            for (MemoryExtractionResult existing : kept) {
                String existingValue = normalizeValue(existing.value());
                if (value.equals(existingValue)) {
                    if (typePriority(candidate.type()) > typePriority(existing.type())) {
                        // 新的一条类型更"硬", 用新的替换旧的
                        continue;
                    }
                    dropNew = true;
                    survivors.add(existing);
                    continue;
                }
                if (value.length() > existingValue.length() && value.contains(existingValue)
                        && existingValue.length() >= 3) {
                    // 旧的是新的子串, 丢掉旧的
                    continue;
                }
                if (existingValue.length() > value.length() && existingValue.contains(value)
                        && value.length() >= 3) {
                    dropNew = true;
                    survivors.add(existing);
                    continue;
                }
                survivors.add(existing);
            }
            kept = survivors;
            if (!dropNew) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.replaceAll(
                "[\\s\\p{Punct}，。！？、；：“”‘’（）《》·…]", "");
    }

    /** 类型优先级: 硬约束/禁令 > 偏好 > 属性 > 经历 */
    private static int typePriority(MemoryItemType type) {
        return switch (type) {
            case CONSTRAINT, VETO -> 5;
            case PREFERENCE -> 3;
            case PROFILE -> 2;
            case EPISODIC -> 1;
        };
    }

    private MemoryExtractionResult convert(JsonNode node) {
        String rawType = node.path("type").asText("").trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(rawType)) {
            return null;
        }
        String key = node.path("key").asText("").trim();
        String value = node.path("value").asText("").trim();
        if (key.isEmpty() || value.isEmpty() || SENSITIVE.matcher(key + value).find()) {
            return null;
        }
        if (PLACEHOLDER_VALUES.contains(value.toLowerCase(Locale.ROOT))) {
            log.debug("[memory-llm] 丢弃占位值候选: key={}, value={}", key, value);
            return null;
        }
        if (value.length() > maxValueChars) {
            value = value.substring(0, maxValueChars);
        }
        BigDecimal confidence = clamp(node.path("confidence").asDouble(0.8));
        BigDecimal importance = clamp(node.path("importance").asDouble(0.6));
        return new MemoryExtractionResult(
                MemoryItemType.valueOf(rawType),
                key,
                value,
                key + "=" + value,
                confidence,
                importance,
                null,
                null);
    }

    private static BigDecimal clamp(double raw) {
        double bounded = Math.max(0.0, Math.min(1.0, raw));
        return BigDecimal.valueOf(bounded).setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private String extractMessage(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            if (node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception e) {
            // payload 允许是纯文本, 解析失败按原文处理
        }
        return payloadJson;
    }
}
