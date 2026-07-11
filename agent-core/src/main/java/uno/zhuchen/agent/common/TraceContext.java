package uno.zhuchen.agent.common;

import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Reactor Context 中 traceId 的 key 常量与读取工具.
 *
 * <p>用于在 reactive 链路中传递链路追踪 ID. 配套使用:
 * <ul>
 *   <li>Controller 入口: {@code .contextWrite(Context.of(TraceContext.KEY, id))}</li>
 *   <li>业务层读取: {@link #currentTraceIdMono()} 或 {@link #currentTraceId(ContextView)}</li>
 * </ul>
 *
 * <p>为什么选 "traceId" 作为 key: 与改造前 MDC key 保持一致, logback pattern
 * 配置 {@code %X{traceId}} 无需任何改动.
 *
 * <p>两条路径传递机制不同, 不可互换:
 * <ul>
 *   <li>ReAct 路径: Controller contextWrite → 业务层 deferContextual 读取 (本工具类)</li>
 *   <li>Graph 路径: GraphStreamRunner 写入 state[INPUT_TRACE_ID] → BaseAgent.apply 从 state 读取</li>
 * </ul>
 *
 * @author Zhu Chen
 * @since 2026-07-05
 */
public final class TraceContext {

    /** Reactor Context 中 traceId 的 key */
    public static final String KEY = "traceId";

    private TraceContext() {
    }

    /**
     * Reactive 路径: 从 Reactor Context 读 traceId, 无值时返回空串.
     *
     * <p>典型用法 (业务 log 内取 traceId):
     * <pre>{@code
     * Flux<StreamChunk> enriched = source.handle((chunk, sink) -> {
     *     String traceId = TraceContext.currentTraceId(sink.currentContext());
     *     log.debug("[traceId={}] ...", traceId);
     *     sink.next(chunk);
     * });
     * }</pre>
     */
    public static String currentTraceId(ContextView ctx) {
        return ctx.getOrEmpty(KEY).map(Object::toString).orElse("");
    }

    /**
     * Reactive 路径: 包成 Mono 返回, 供 deferContextual / then / flatMap 等场景使用.
     *
     * <p>典型用法 (调用同步工具前取值, 见 ReactAgent):
     * <pre>{@code
     * String traceId = TraceContext.currentTraceIdMono().block();
     * AskUserTool.setContext(convId, traceId);
     * }</pre>
     */
    public static Mono<String> currentTraceIdMono() {
        return Mono.deferContextual(ctx -> Mono.just(currentTraceId(ctx)));
    }
}