package uno.zhuchen.agent.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天内部流转 DTO — 贯穿 Controller → Agent → Memory 的完整数据载体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatDTO {

    private String conversationId;
    private String userMessage;
    private String answer;
    private int iterationCount;
    private boolean maxIterationsReached;
    private List<String> reasoningSteps;
    private String errorMessage;
    private long durationMs;

    // --- 以下工厂方法包装 builder，提供更友好的调用 API ---

    public static ChatDTO success(String conversationId, String userMessage,
                                   String answer, int iterationCount,
                                   List<String> reasoningSteps, long durationMs) {
        return ChatDTO.builder()
                .conversationId(conversationId)
                .userMessage(userMessage)
                .answer(answer)
                .iterationCount(iterationCount)
                .reasoningSteps(reasoningSteps)
                .durationMs(durationMs)
                .build();
    }

    public static ChatDTO maxIterations(String conversationId, String userMessage,
                                         String partialAnswer, int iterationCount,
                                         long durationMs) {
        return ChatDTO.builder()
                .conversationId(conversationId)
                .userMessage(userMessage)
                .answer(partialAnswer)
                .iterationCount(iterationCount)
                .maxIterationsReached(true)
                .durationMs(durationMs)
                .build();
    }

    public static ChatDTO error(String conversationId, String userMessage,
                                 String errorMessage, long durationMs) {
        return ChatDTO.builder()
                .conversationId(conversationId)
                .userMessage(userMessage)
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .build();
    }
}
