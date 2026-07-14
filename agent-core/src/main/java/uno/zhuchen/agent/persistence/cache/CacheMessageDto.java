package uno.zhuchen.agent.persistence.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 缓存消息 DTO，用于序列化/反序列化 Spring AI Message。
 *
 * 避免直接序列化 Spring AI 内部类型，通过 role + content + metadata
 * 以及可选的 toolCalls / toolResponses 结构实现可靠的 JSON 存储。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CacheMessageDto {
    private String role;               // user / assistant / system / tool
    private String content;
    private Map<String, Object> metadata;

    // assistant 消息的 tool_calls（如有）
    private List<CacheToolCallDto> toolCalls;

    // tool 响应消息（如有）
    private List<CacheToolResponseDto> toolResponses;

    public CacheMessageDto(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public CacheMessageDto(String role, String content, Map<String, Object> metadata) {
        this.role = role;
        this.content = content;
        this.metadata = metadata;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CacheToolCallDto {
        private String id;
        private String type;       // "function"
        private String name;
        private String arguments;  // JSON
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CacheToolResponseDto {
        private String id;         // tool_call_id
        private String name;       // tool name
        private String responseData;
    }
}
