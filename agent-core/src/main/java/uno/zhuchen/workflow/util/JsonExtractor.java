package uno.zhuchen.workflow.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 LLM 输出中提取 JSON 的健壮工具。
 *
 * <p>LLM 的输出常常包含 markdown 代码块、多余文字、甚至截断的 JSON。
 * 本工具按优先级尝试多种策略提取。</p>
 *
 * <p>提取策略（按优先级）：</p>
 * <ol>
 *   <li>```json ... ``` 代码块中的内容（最可靠）</li>
 *   <li>``` ... ``` 未指定语言的代码块</li>
 *   <li>直接尝试解析原始字符串（本身就是干净 JSON）</li>
 *   <li>贪婪匹配第一个 { ... } 或 [ ... ] 顶层结构</li>
 *   <li>截断修复：尝试补全不完整的 JSON</li>
 * </ol>
 */
public class JsonExtractor {

    private static final Logger log = LoggerFactory.getLogger(JsonExtractor.class);

    /** ```json ... ``` 代码块 */
    private static final Pattern CODE_BLOCK_JSON = Pattern.compile(
            "(?s)```(?:json)\\s*([\\s\\S]*?)```"
    );

    /** ``` ... ``` 无语言标记的代码块 */
    private static final Pattern CODE_BLOCK_RAW = Pattern.compile(
            "(?s)```\\s*([\\s\\S]*?)```"
    );

    /** 匹配顶层 JSON 对象：从第一个 { 到匹配的 } */
    private static final Pattern TOP_LEVEL_OBJECT = Pattern.compile(
            "(?s)\\{(?:[^{}]|(?:\\{[^{}]*\\}))*\\}"
    );

    /** 匹配顶层 JSON 数组：从第一个 [ 到匹配的 ] */
    private static final Pattern TOP_LEVEL_ARRAY = Pattern.compile(
            "(?s)\\[(?:[^\\[\\]]|(?:\\[[^\\[\\]]*\\]))*\\]"
    );

    /** 回退：最短 { ... } 匹配（可能不准确，作为最后手段） */
    private static final Pattern FALLBACK_OBJECT = Pattern.compile(
            "(?s)\\{(?:[^{}]|\\{[^{}]*\\})*\\}"
    );

    private final ObjectMapper objectMapper;

    public JsonExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 LLM 输出中提取 JSON 并解析为 JsonNode。
     *
     * @param raw LLM 原始输出
     * @return 解析成功返回 JsonNode，失败返回 null
     */
    public JsonNode extract(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("[JsonExtractor] 输入为空");
            return null;
        }

        String trimmed = raw.trim();

        // 策略 1: ```json ... ``` 代码块
        JsonNode result = tryExtract(trimmed, CODE_BLOCK_JSON, 1);
        if (result != null) return result;

        // 策略 2: ``` ... ``` 代码块（无语言标记）
        result = tryExtract(trimmed, CODE_BLOCK_RAW, 1);
        if (result != null) return result;

        // 策略 3: 直接解析原始字符串
        result = tryParse(trimmed);
        if (result != null) return result;

        // 策略 4: 顶层 { ... } 对象
        result = tryExtract(trimmed, TOP_LEVEL_OBJECT, 0);
        if (result != null) return result;

        // 策略 5: 顶层 [ ... ] 数组 → 包装为对象
        result = tryExtractArray(trimmed, TOP_LEVEL_ARRAY, 0);
        if (result != null) return result;

        // 策略 6: 回退——最短 { ... }
        result = tryExtract(trimmed, FALLBACK_OBJECT, 0);
        if (result != null) return result;

        log.warn("[JsonExtractor] 所有策略均无法提取 JSON, input长度={}, 原始内容:\n---\n{}\n---",
                trimmed.length(), trimmed.length() <= 5000 ? trimmed : trimmed.substring(0, 5000) + "\n... (截断, 总" + trimmed.length() + "字符)");
        return null;
    }

    /**
     * 提取并深拷贝为 JsonNode（方便链式调用）
     */
    public JsonNode extractDeepCopy(String raw) {
        JsonNode node = extract(raw);
        if (node == null) return null;
        return node.deepCopy();
    }

    // ====== 内部方法 ======

    /**
     * 用正则提取 group 并解析
     */
    private JsonNode tryExtract(String input, Pattern pattern, int group) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            String candidate = matcher.group(group).trim();
            JsonNode result = tryParse(candidate);
            if (result != null) {
                log.debug("[JsonExtractor] 通过正则 {} 提取成功, 长度={}", pattern.pattern(), candidate.length());
                return result;
            }
            // 可能是截断的 JSON，尝试修复
            JsonNode fixed = tryRepair(candidate);
            if (fixed != null) return fixed;
        }
        return null;
    }

    /**
     * 提取数组并包装为对象
     */
    private JsonNode tryExtractArray(String input, Pattern pattern, int group) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            String candidate = matcher.group(group).trim();
            try {
                // 先尝试直接解析为 JsonNode（可能是数组）
                JsonNode arrayNode = objectMapper.readTree(candidate);
                log.debug("[JsonExtractor] 提取数组成功, 长度={}", candidate.length());
                // 包装成 {"items": [...]} 形式的对象
                return objectMapper.createObjectNode().set("items", arrayNode);
            } catch (JsonProcessingException e) {
                JsonNode fixed = tryRepair(candidate);
                if (fixed != null) {
                    // 修复后的数组也包装
                    try {
                        return objectMapper.createObjectNode().set("items", fixed);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    /**
     * 尝试直接解析为 JsonNode
     */
    private JsonNode tryParse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 尝试修复不完整的 JSON（截断修复）
     *
     * <p>LLM 输出可能被截断，例如：<pre>
     * {"days": [{"dayIndex":1,"pois":[{"name":"故宫"
     * </pre>
     * 我们尝试补充缺失的引号、括号。</p>
     */
    private JsonNode tryRepair(String incomplete) {
        if (incomplete == null || incomplete.isEmpty()) return null;

        String repaired = incomplete;

        // 补全未闭合的引号
        int quoteCount = countChar(repaired, '"');
        if (quoteCount % 2 != 0) {
            repaired = repaired + '"';
        }

        // 补全未闭合的花括号
        int openBraces = countChar(repaired, '{');
        int closeBraces = countChar(repaired, '}');
        for (int i = 0; i < openBraces - closeBraces; i++) {
            repaired = repaired + "}";
        }

        // 补全未闭合的方括号
        int openBrackets = countChar(repaired, '[');
        int closeBrackets = countChar(repaired, ']');
        for (int i = 0; i < openBrackets - closeBrackets; i++) {
            repaired = repaired + "]";
        }

        if (repaired.equals(incomplete)) {
            return null; // 没有修复任何内容
        }

        try {
            JsonNode result = objectMapper.readTree(repaired);
            log.debug("[JsonExtractor] 截断修复成功, 原长度={}, 修复后长度={}",
                    incomplete.length(), repaired.length());
            return result;
        } catch (JsonProcessingException e) {
            // 修复后仍无效
            log.trace("[JsonExtractor] 截断修复失败: {}", e.getMessage());
            return null;
        }
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}
