package uno.zhuchen.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 模型注册表 — 绑定 {@code app.models} 配置, 提供模型查找与默认模型解析.
 *
 * <p>配置驱动扩展: 新增模型只需在 application.yml 的 app.models 加一项,
 * 列表顺序即前端展示顺序, 第一项为默认模型(前端默认选中项, 未传 modelId 时后端回退项).
 *
 * <p>注: 绑定前缀为整个 {@code app}, 同前缀的 app.cache.* 等属性无对应字段,
 * 依赖 @ConfigurationProperties 默认的 ignoreUnknownFields 行为自动忽略.
 */
@Component
@ConfigurationProperties(prefix = "app")
@Data
public class ModelRegistry {

    private List<ModelConfig> models = new ArrayList<>();

    /** 默认模型 = 列表第一项(deepseek 置顶即默认) */
    public ModelConfig defaultModel() {
        return models.isEmpty() ? null : models.get(0);
    }

    public Optional<ModelConfig> findById(String id) {
        return models.stream().filter(m -> m.getId().equals(id)).findFirst();
    }

    @Data
    public static class ModelConfig {
        /** 业务 ID, 前端选择与请求体携带的是这个 */
        private String id;
        /** provider 键, 与 ModelRouter 的路由 map key 对应 */
        private String provider;
        /** API 模型名, 实际传给 LLM 的值 */
        private String model;
        /** 前端展示名 */
        private String displayName;
    }
}
