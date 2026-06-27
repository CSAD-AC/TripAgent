package uno.zhuchen.workflow.state;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验报告 — ValidationAgent 输出
 *
 * 校验分两类:
 * - 静态约束（数值比较）: budget / days / companions
 * - 软约束（LLM 推理）: Constraints.softRequirements
 *
 * 软约束失败不一定是错误，可能仅是 warning（不强阻断）。
 * passed=false 时 ManagerAgent 需要重新调度。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidationReport {

    /** 是否通过校验（无 failure 即通过） */
    @Builder.Default
    private Boolean passed = true;

    /**
     * 失败项 — 触发 ManagerAgent 回退
     *
     * 例子: budget 超支、行程天数不匹配、软约束未满足等
     */
    @Builder.Default
    private List<Failure> failures = new ArrayList<>();

    /**
     * 警告项 — 不阻断流程，仅在终态报告里提示
     *
     * 例子: 行程略紧、某景点门票较贵但合理
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /**
     * 失败项详情
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Failure {

        /** 失败维度: "static.budget" / "static.days" / "soft.requirement" */
        private String dimension;

        /** 具体失败字段或软约束原文 */
        private String requirement;

        /** 失败原因（人类可读） */
        private String reason;

        /**
         * 修改建议（给 ManagerAgent / LLM 用）
         * 例如 "减少 1 个景点", "替换为更便宜的酒店"
         */
        private String suggestion;
    }
}
