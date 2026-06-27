package uno.zhuchen.workflow.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 提取的结构化约束 — ManagerAgent 输出
 *
 * 字段分组:
 * - 基础信息: destination / days / companions
 * - 预算: budget
 * - 偏好: preferences（自然/文化等枚举值）
 * - 软约束: softRequirements（自然语言，LLM 推理校验）
 *
 * 软约束设计动机: 用户可能提"中途要去游乐园玩一次"这种
 * 静态规则无法表达的个性化需求，ValidationAgent 用 LLM 逐条推理。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Constraints {

    /** 目的地（如"北京"） */
    private String destination;

    /** 旅行天数 */
    private Integer days;

    /** 预算（人民币元） */
    private Integer budget;

    /** 同行人数 */
    private Integer companions;

    /** 偏好列表（如 "自然", "文化", "美食"） */
    @Builder.Default
    private List<String> preferences = new ArrayList<>();

    /**
     * 软约束列表 — 自然语言描述的个性化需求
     *
     * 例子:
     * - "中途要去游乐园玩一次"
     * - "想品尝当地特色小吃"
     * - "希望住宿靠近地铁"
     *
     * ValidationAgent 对每条做 met/notMet 推理。
     */
    @Builder.Default
    private List<String> softRequirements = new ArrayList<>();

    /**
     * 校验必填字段是否齐备
     *
     * @return 缺失的字段名列表；空列表表示齐备
     */
    public List<String> missingRequiredFields() {
        List<String> missing = new ArrayList<>();
        if (destination == null || destination.isBlank()) {
            missing.add("destination");
        }
        if (days == null) {
            missing.add("days");
        }
        if (budget == null) {
            missing.add("budget");
        }
        if (companions == null) {
            missing.add("companions");
        }
        return missing;
    }

    /**
     * 是否所有必填字段都已就绪
     */
    public boolean isComplete() {
        return missingRequiredFields().isEmpty();
    }
}
