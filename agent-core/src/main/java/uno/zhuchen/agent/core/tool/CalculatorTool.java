package uno.zhuchen.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 计算器工具 — 供 LLM 做精确的数学运算。
 *
 * <p>LLM 在做预算计算、费用分摊等场景时，直接调用此工具
 * 避免大模型算术不准确的缺陷。</p>
 *
 * <p>支持: add, subtract, multiply, divide, sum, percentage</p>
 */
@Component
public class CalculatorTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);

    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public CalculatorTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.definition = ToolDefinition.builder()
                .name("calculator")
                .description("""
                        精确计算器。支持以下运算:
                        - add(x,y): 加法 x+y
                        - subtract(x,y): 减法 x-y
                        - multiply(x,y): 乘法 x*y
                        - divide(x,y): 除法 x/y
                        - sum(values): 求和
                        - percentage(part,total): 计算百分比
                        当需要做预算计算、费用分摊等数学运算时使用此工具，避免算术错误。
                        """)
                .inputSchema("""
                    {
                        "type": "object",
                        "properties": {
                            "op": {
                                "type": "string",
                                "enum": ["add","subtract","multiply","divide","sum","percentage"],
                                "description": "运算类型"
                            },
                            "x": {
                                "type": "number",
                                "description": "第一个操作数（sum/percentage 不用）"
                            },
                            "y": {
                                "type": "number",
                                "description": "第二个操作数（sum/percentage 不用）"
                            },
                            "values": {
                                "type": "array",
                                "items": {"type": "number"},
                                "description": "数值列表（仅 sum 用）"
                            },
                            "part": {
                                "type": "number",
                                "description": "部分值（仅 percentage 用）"
                            },
                            "total": {
                                "type": "number",
                                "description": "总值（仅 percentage 用）"
                            }
                        },
                        "required": ["op"]
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
        log.debug("CalculatorTool.call: input={}", toolInput);
        try {
            JsonNode args = objectMapper.readTree(toolInput);
            String op = args.hasNonNull("op") ? args.get("op").asText() : "";

            double result;
            String expr;

            switch (op) {
                case "add": {
                    double x = args.get("x").asDouble();
                    double y = args.get("y").asDouble();
                    result = x + y;
                    expr = formatNum(x) + " + " + formatNum(y);
                    break;
                }
                case "subtract": {
                    double x = args.get("x").asDouble();
                    double y = args.get("y").asDouble();
                    result = x - y;
                    expr = formatNum(x) + " - " + formatNum(y);
                    break;
                }
                case "multiply": {
                    double x = args.get("x").asDouble();
                    double y = args.get("y").asDouble();
                    result = x * y;
                    expr = formatNum(x) + " × " + formatNum(y);
                    break;
                }
                case "divide": {
                    double x = args.get("x").asDouble();
                    double y = args.get("y").asDouble();
                    if (y == 0) {
                        return "错误: 除数不能为0";
                    }
                    result = x / y;
                    expr = formatNum(x) + " ÷ " + formatNum(y);
                    break;
                }
                case "sum": {
                    double sum = 0;
                    if (args.has("values") && args.get("values").isArray()) {
                        for (JsonNode v : args.get("values")) {
                            sum += v.asDouble();
                        }
                    }
                    result = sum;
                    expr = "求和";
                    break;
                }
                case "percentage": {
                    double part = args.get("part").asDouble();
                    double total = args.get("total").asDouble();
                    if (total == 0) {
                        return "错误: 总值不能为0";
                    }
                    result = (part / total) * 100;
                    expr = formatNum(part) + " / " + formatNum(total) + " × 100%";
                    break;
                }
                default:
                    return "错误: 不支持的运算 '" + op + "'";
            }

            // 如果是整数则返回整数形式
            String resultStr;
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                resultStr = String.valueOf((long) result);
            } else {
                resultStr = String.format("%.2f", result);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("expression", expr);
            response.put("result", resultStr);
            response.put("description", expr + " = " + resultStr);

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("CalculatorTool 执行失败: {}", e.getMessage(), e);
            return "计算器调用失败: " + e.getMessage();
        }
    }

    private static String formatNum(double n) {
        if (n == Math.floor(n) && !Double.isInfinite(n)) {
            return String.valueOf((long) n);
        }
        return String.format("%.2f", n);
    }
}
