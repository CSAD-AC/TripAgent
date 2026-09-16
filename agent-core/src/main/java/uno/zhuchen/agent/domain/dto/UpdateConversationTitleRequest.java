package uno.zhuchen.agent.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 更新对话标题请求 DTO — 修改会话标题时的入参契约.
 *
 * <p>替代原先 {@code @RequestBody Map<String,String>} 的弱类型接收,
 * 用强类型 DTO + {@code @Valid} 校验, 与项目接口风格一致.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateConversationTitleRequest {

    /** 新标题(非空) */
    @NotBlank(message = "title 不能为空")
    @Size(max = 128, message = "标题不能超过 128 字符")
    private String title;
}