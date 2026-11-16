package uno.zhuchen.workflow.util;

import org.springframework.stereotype.Component;
import uno.zhuchen.agent.domain.dto.StreamChunk;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Routes Graph events to the sink registered for their conversation.
 *
 * <p>Agent instances are Spring singletons. Keeping a per-run emitter on those
 * singletons means a newer run can overwrite the emitter used by an older run.
 * A shared routing emitter avoids that cross-conversation leak.
 */
@Component
public class GraphEventRouter {

    private final ConcurrentMap<String, GraphEventEmitter> emitters = new ConcurrentHashMap<>();
    private final GraphEventEmitter routingEmitter = this::route;

    public GraphEventEmitter emitter() {
        return routingEmitter;
    }

    public void register(String conversationId, GraphEventEmitter emitter) {
        if (conversationId != null && !conversationId.isBlank() && emitter != null) {
            emitters.put(conversationId, emitter);
        }
    }

    public void unregister(String conversationId, GraphEventEmitter emitter) {
        if (conversationId != null && emitter != null) {
            emitters.remove(conversationId, emitter);
        }
    }

    private void route(StreamChunk chunk) {
        if (chunk == null || chunk.getConversationId() == null) {
            return;
        }
        GraphEventEmitter target = emitters.get(chunk.getConversationId());
        if (target != null) {
            target.emit(chunk);
        }
    }
}
