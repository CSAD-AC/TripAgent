package uno.zhuchen.agent.tools.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMemoryVectorStoreTest {

    @Test
    void filterExpressionAlwaysCarriesTenantAndUser() {
        String filter = UserMemoryVectorStore.filterExpression("default", "u1", null);

        assertTrue(filter.contains("tenant_id == 'default'"));
        assertTrue(filter.contains("user_id == 'u1'"));
        assertTrue(filter.contains("status == 'active'"));
        assertTrue(!filter.contains("project_id"));
    }

    @Test
    void filterExpressionAddsProjectWhenPresent() {
        String filter = UserMemoryVectorStore.filterExpression("default", "u1", "p9");

        assertTrue(filter.contains("project_id == 'p9'"));
    }

    @Test
    void filterExpressionRejectsMissingUserDimension() {
        assertEquals("", UserMemoryVectorStore.filterExpression("default", " ", null));
        assertEquals("", UserMemoryVectorStore.filterExpression(null, "u1", null));
    }

    @Test
    void filterExpressionStripsInjectionCharacters() {
        String filter = UserMemoryVectorStore.filterExpression("default", "u1' || user_id != '", null);

        assertTrue(filter.contains("user_id == 'u1user_id'"));
    }
}
