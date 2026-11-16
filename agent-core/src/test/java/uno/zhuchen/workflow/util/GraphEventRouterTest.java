package uno.zhuchen.workflow.util;

import org.junit.jupiter.api.Test;
import uno.zhuchen.agent.domain.dto.StreamChunk;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphEventRouterTest {

    @Test
    void routesEventsByConversation() {
        GraphEventRouter router = new GraphEventRouter();
        List<StreamChunk> first = new ArrayList<>();
        List<StreamChunk> second = new ArrayList<>();
        GraphEventEmitter firstEmitter = first::add;
        GraphEventEmitter secondEmitter = second::add;
        router.register("conversation-1", firstEmitter);
        router.register("conversation-2", secondEmitter);

        router.emitter().emit(StreamChunk.nodeStatus("manager", "running", "conversation-1"));
        router.emitter().emit(StreamChunk.nodeStatus("manager", "running", "conversation-2"));
        router.emitter().emit(StreamChunk.nodeStatus("manager", "done", "conversation-1"));

        assertThat(first).extracting(StreamChunk::getConversationId)
                .containsExactly("conversation-1", "conversation-1");
        assertThat(second).extracting(StreamChunk::getConversationId)
                .containsExactly("conversation-2");
    }

    @Test
    void unregisterStopsRoutingToOldSink() {
        GraphEventRouter router = new GraphEventRouter();
        List<StreamChunk> events = new ArrayList<>();
        GraphEventEmitter emitter = events::add;
        router.register("conversation-1", emitter);
        router.unregister("conversation-1", emitter);

        router.emitter().emit(StreamChunk.nodeStatus("manager", "running", "conversation-1"));

        assertThat(events).isEmpty();
    }
}
