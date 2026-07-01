package uno.zhuchen.workflow.util;

import uno.zhuchen.agent.domain.dto.StreamChunk;

/**
 * Graph 工作流事件发射器 — C1 修复配套
 *
 * <p>原本用 {@code ThreadLocal<Consumer<StreamChunk>>} 传递 emitter，但 Spring AI Alibaba
 * Graph 的并行节点（route + itinerary）会切换线程，导致只有一个节点的事件能被正确发射。
 * <p>改为构造器注入：每个 Agent 实例持有自己的 emitter 引用，
 * 在并行节点中即使切换线程，emitter 仍然指向同一个 sink。
 */
public interface GraphEventEmitter {

    /**
     * 把事件发到 Graph 事件管道，由 GraphStreamRunner 推给 SSE 客户端。
     * <p>实现必须是线程安全的（背后通常基于 reactor {@code Sinks.Many}）。
     */
    void emit(StreamChunk chunk);
}