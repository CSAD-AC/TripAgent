package uno.zhuchen.agent.tools.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserMemoryVectorPropertiesTest {

    @Test
    void defaultsUseDedicatedUserMemoryCollection() {
        UserMemoryVectorProperties properties = new UserMemoryVectorProperties();

        assertEquals("user_memory_episodic_v1", properties.getCollectionName());
        assertEquals(1024, properties.getEmbeddingDimension());
    }
}
