package uno.zhuchen.agent.persistence.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 缓存消息 DTO，用于序列化/反序列化 Spring AI Message。
 *
 * 避免直接序列化 Spring AI 内部类型，通过简单的 role + content + metadata
 * 结构实现可靠的 JSON 存储。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheMessageDto {
    private String role;               // user / assistant / system / tool
    private String content;
    private Map<String, Object> metadata;

    public CacheMessageDto(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
