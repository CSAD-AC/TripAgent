package uno.zhuchen.agent.common;

import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Reactor Context 中 modelId 的 key 常量与读取工具.
 *
 * <p>与 {@link TraceContext} 同构: Controller 入口 contextWrite 注入,
 * 业务层(ReactAgent 流式路径)deferContextual 读取, Reactor Context 自动跨订阅边界.
 *
 * <p>为什么走 Context 而非参数: 与 traceId 相同, 流式递归(nextIteration)无需
 * 显式透传, 订阅链自动携带; 同步路径(call)则直接用方法参数传递.
 */
public final class ModelContext {

    /** Reactor Context 中 modelId 的 key(业务 ID, 如 deepseek/qwen) */
    public static final String KEY = "modelId";

    private ModelContext() {
    }

    /** 从 Context 读 modelId, 无值时返回 null(默认模型) */
    public static String currentModelId(ContextView ctx) {
        return ctx.getOrEmpty(KEY).map(Object::toString).orElse(null);
    }

    /** 包成 Mono 返回, 供 deferContextual / then / flatMap 等场景使用 */
    public static Mono<String> currentModelIdMono() {
        return Mono.deferContextual(ctx -> Mono.justOrEmpty(currentModelId(ctx)));
    }
}
