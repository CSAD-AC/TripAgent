package uno.zhuchen.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AskUserTool 的 ToolCallback 适配器
 *
 * <p>Spring AI 的 MCP 客户端只扫描 MCP 服务端工具，不会自动发现本地 @Tool 注解。
 * 本类手动把 {@link AskUserTool} 包装成 ToolCallback，注册进 ToolRegistry，
 * 让 ReactAgent 把它暴露给 LLM。
 *
 * <p>职责：
 * <ul>
 *   <li>getToolDefinition: 暴露工具 schema (name, description, inputSchema) 给 LLM</li>
 *   <li>call: 解析 JSON 入参 → 调用 AskUserTool.askUser() → 返回字符串结果</li>
 * </ul>
 */
@Component
public class AskUserToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(AskUserToolCallback.class);

    private final AskUserTool askUserTool;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public AskUserToolCallback(AskUserTool askUserTool, ObjectMapper objectMapper) {
        this.askUserTool = askUserTool;
        this.objectMapper = objectMapper;
        this.definition = ToolDefinition.builder()
                .name("askUser")
                .description("向用户反问以补充关键信息。仅当缺少必要信息无法继续时才调用，"
                        + "问题要简短清晰,2-4 个预设选项,allowCustom 必须传 true 以便用户自由补充。"
                        + "不要为可有可无的信息打断用户,只在关键字段缺失导致无法继续时使用。")
                .inputSchema("""
                    {
                        "type": "object",
                        "properties": {
                            "question": {
                                "type": "string",
                                "description": "向用户提出的问题,简短清晰,例如「预算范围是?」"
                            },
                            "options": {
                                "type": "array",
                                "description": "预设选项列表,2-4 个,每个含 label 和 value",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "label": {"type": "string", "description": "显示文本"},
                                        "value": {"type": "string", "description": "传给 LLM 的值"}
                                    },
                                    "required": ["label", "value"]
                                }
                            },
                            "allowCustom": {
                                "type": "boolean",
                                "description": "是否允许用户自由输入,默认 true"
                            }
                        },
                        "required": ["question", "options"]
                    }
                    """)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        log.debug("AskUserToolCallback.call: input={}", toolInput);
        try {
            JsonNode args = objectMapper.readTree(toolInput);

            String question = args.hasNonNull("question") ? args.get("question").asText() : null;
            if (question == null || question.isBlank()) {
                return "错误: question 参数不能为空";
            }

            List<AskUserTool.Option> options = parseOptions(args.get("options"));
            if (options.isEmpty()) {
                return "错误: options 至少需要 1 个选项";
            }

            Boolean allowCustom = args.has("allowCustom") && !args.get("allowCustom").isNull()
                    ? args.get("allowCustom").asBoolean()
                    : null;

            // 调用真正的反问工具
            return askUserTool.askUser(question, options, allowCustom);
        } catch (Exception e) {
            log.error("AskUserToolCallback 解析失败: {}", e.getMessage(), e);
            return "反问工具调用失败: " + e.getMessage();
        }
    }

    private List<AskUserTool.Option> parseOptions(JsonNode optionsNode) {
        List<AskUserTool.Option> options = new ArrayList<>();
        if (optionsNode == null || !optionsNode.isArray()) {
            return options;
        }
        for (JsonNode opt : optionsNode) {
            String label = opt.hasNonNull("label") ? opt.get("label").asText() : null;
            String value = opt.hasNonNull("value") ? opt.get("value").asText() : label;
            if (label != null && !label.isBlank()) {
                options.add(new AskUserTool.Option(label, value));
            }
        }
        return options;
    }
}
