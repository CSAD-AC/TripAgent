package uno.zhuchen.workflow.agent;

import org.junit.jupiter.api.Test;
import uno.zhuchen.workflow.state.Constraints;
import uno.zhuchen.workflow.state.ValidationReport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationAgentPreferencePolicyTest {

    @Test
    void demotesGenericPreferenceFailureToWarning() {
        Constraints constraints = Constraints.builder()
                .preferences(List.of("美食", "自然"))
                .softRequirements(List.of())
                .build();
        ValidationReport report = reportWithFailure(
                "偏好[美食,自然]", "自然类景点仅一处", "增加自然景点");

        ValidationReport adjusted = ValidationAgent.applyPreferencePolicy(report, constraints);

        assertThat(adjusted.getPassed()).isTrue();
        assertThat(adjusted.getFailures()).isEmpty();
        assertThat(adjusted.getWarnings())
                .contains("偏好提醒:偏好[美食,自然]:自然类景点仅一处");
    }

    @Test
    void keepsExplicitSoftRequirementFailureBlocking() {
        Constraints constraints = Constraints.builder()
                .preferences(List.of("亲子"))
                .softRequirements(List.of("中途要去一次游乐园"))
                .build();
        ValidationReport report = reportWithFailure(
                "中途要去一次游乐园", "行程未安排游乐园", "加入游乐园");

        ValidationReport adjusted = ValidationAgent.applyPreferencePolicy(report, constraints);

        assertThat(adjusted.getPassed()).isFalse();
        assertThat(adjusted.getFailures()).hasSize(1);
        assertThat(adjusted.getWarnings()).isEmpty();
    }

    @Test
    void missingFailuresAndFalsePassedStaysBlocking() {
        Constraints constraints = Constraints.builder()
                .preferences(List.of("自然"))
                .build();
        ValidationReport report = ValidationReport.builder()
                .passed(false)
                .failures(List.of())
                .warnings(List.of())
                .build();

        ValidationReport adjusted = ValidationAgent.applyPreferencePolicy(report, constraints);

        assertThat(adjusted.getPassed()).isFalse();
    }

    private ValidationReport reportWithFailure(String requirement, String reason, String suggestion) {
        return ValidationReport.builder()
                .passed(false)
                .failures(List.of(ValidationReport.Failure.builder()
                        .dimension("soft.requirement")
                        .requirement(requirement)
                        .reason(reason)
                        .suggestion(suggestion)
                        .build()))
                .warnings(List.of())
                .build();
    }
}
