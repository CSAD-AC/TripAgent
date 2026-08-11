package uno.zhuchen.agent.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uno.zhuchen.agent.config.ModelRegistry;

/**
 * 模型信息 VO — 返回给前端渲染模型选择列表.
 *
 * <p>顺序即注册表配置顺序(deepseek 置顶), 前端取第一项作为默认选中.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelVO {

    private String id;
    private String provider;
    private String model;
    private String displayName;

    public static ModelVO from(ModelRegistry.ModelConfig cfg) {
        return ModelVO.builder()
                .id(cfg.getId())
                .provider(cfg.getProvider())
                .model(cfg.getModel())
                .displayName(cfg.getDisplayName())
                .build();
    }
}
