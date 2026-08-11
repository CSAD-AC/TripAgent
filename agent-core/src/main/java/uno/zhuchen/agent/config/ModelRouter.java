package uno.zhuchen.agent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import uno.zhuchen.agent.core.llm.ChatModel;

import java.util.Map;

/**
 * 模型路由 — 把 modelId(或默认)解析为配置项与对应的 ChatModel 实现.
 *
 * <p>职责:
 * <ul>
 *   <li>resolveConfig: modelId → ModelConfig; null/空串 → 默认(注册表第一项);
 *       未知 modelId → 抛 IllegalArgumentException(Controller 全局异常转 400)</li>
 *   <li>resolveModelName: modelId → API 模型名(传给 LLM 的值)</li>
 *   <li>route: provider → ChatModel 实现(deepseek / dashscope 双 bean 并存)</li>
 * </ul>
 *
 * <p>设计: provider 字符串作为路由键, 注册表配置与 bean 装配解耦 —
 * 未来新增 provider 只需: ① 加配置项 ② 加 ChatModel bean ③ 这里补一个映射.
 */
@Component
public class ModelRouter {

    private final ModelRegistry registry;
    private final Map<String, ChatModel> providers;

    public ModelRouter(ModelRegistry registry,
                       @Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel,
                       @Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel) {
        this.registry = registry;
        this.providers = Map.of(
                "deepseek", deepSeekChatModel,
                "dashscope", dashScopeChatModel);
    }

    /** 解析 modelId 为配置项; null/空串 → 默认(第一项); 未知 → 400 */
    public ModelRegistry.ModelConfig resolveConfig(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            ModelRegistry.ModelConfig def = registry.defaultModel();
            if (def == null) {
                throw new IllegalStateException("模型注册表为空, 请检查 app.models 配置");
            }
            return def;
        }
        return registry.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException("未知模型 ID: " + modelId));
    }

    /** modelId → API 模型名; null/空串 → 默认模型的 API 名 */
    public String resolveModelName(String modelId) {
        return resolveConfig(modelId).getModel();
    }

    /** modelId → ChatModel 实现(按 provider 路由) */
    public ChatModel route(String modelId) {
        ModelRegistry.ModelConfig cfg = resolveConfig(modelId);
        ChatModel impl = providers.get(cfg.getProvider());
        if (impl == null) {
            throw new IllegalStateException("未注册的 provider: " + cfg.getProvider());
        }
        return impl;
    }

    /** 全部模型(按配置顺序, deepseek 置顶) — 供 /api/models 返回 */
    public java.util.List<ModelRegistry.ModelConfig> listAll() {
        return registry.getModels();
    }
}
