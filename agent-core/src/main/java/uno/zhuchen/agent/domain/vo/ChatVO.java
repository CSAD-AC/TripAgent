package uno.zhuchen.agent.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uno.zhuchen.agent.domain.dto.ChatDTO;

/**
 * 聊天视图对象 — 前端展示层，按需裁剪/格式化 ChatDTO 中的字段
 *
 * 唯一构造来源：{@link #from(ChatDTO)}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatVO {

    private String answer;
    private String conversationId;
    private String displayStatus;
    private long durationMs;

    /**
     * 从内部流转 DTO 构建 VO
     */
    public static ChatVO from(ChatDTO dto) {
        ChatVO vo = new ChatVO();
        vo.setAnswer(dto.getAnswer());
        vo.setConversationId(dto.getConversationId());
        vo.setDurationMs(dto.getDurationMs());

        if (dto.getErrorMessage() != null) {
            vo.setDisplayStatus("出错了");
            vo.setAnswer("抱歉，处理时遇到问题：" + dto.getErrorMessage());
        } else if (dto.isMaxIterationsReached()) {
            vo.setDisplayStatus("已达思考上限");
        } else {
            vo.setDisplayStatus("已完成");
        }
        return vo;
    }
}
