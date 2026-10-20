package uno.zhuchen.agent.mcpserver.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 迁移实跑测试: 在容器化的干净 MySQL 8.4 上执行 db/rag-migration 的 V2.
 *
 * <p>与 agent-core 的迁移刻意隔离:
 * <ul>
 *   <li>location 不同: classpath:db/rag-migration (不是 db/migration)</li>
 *   <li>历史表不同: rag_schema_history (不是 flyway_schema_history)</li>
 *   <li>版本从 V2 起始, 没有 V1 -- 验证 Flyway 允许版本号不从 1 开始</li>
 * </ul>
 *
 * <p>不依赖开发者本机 MySQL; {@code disabledWithoutDocker = true} 保证无 Docker 的机器 SKIP 而非 FAIL.
 *
 * <p>注意: 本测试**故意不加** {@code @Tag("integration")}, 否则会被主 CI 的
 * {@code -DexcludedGroups=integration} 排除.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("mcp-server RAG Flyway 迁移: 在干净 MySQL 8.4 上实跑 V2")
class RagFlywayMigrationTest {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("trip_rag")
                    .withUsername("rag")
                    .withPassword("rag_pass");

    private static final String MIGRATION_LOCATION = "classpath:db/rag-migration";

    /** 与 mcp-server/application.yml 的 spring.flyway.table 一致, 与 agent-core 互不干扰. */
    private static final String HISTORY_TABLE = "rag_schema_history";

    private static Flyway flyway;
    private static MigrateResult firstMigrate;

    /** 与 mcp-server/application.yml 的 spring.flyway 配置对齐. */
    private static Flyway newFlyway() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations(MIGRATION_LOCATION)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .load();
    }

    /** 迁移只跑一次, 后续测试只做只读断言, 因此测试方法之间与执行顺序无关. */
    @BeforeAll
    static void migrateOnce() {
        flyway = newFlyway();
        firstMigrate = flyway.migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    // ==================== 查询辅助 ====================

    private static List<String> appliedVersions() throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT version FROM " + HISTORY_TABLE
                             + " WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    private static boolean tableExists(String table) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static String columnType(String table, String column) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT column_type FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean indexExists(String table, String index) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.statistics "
                             + "WHERE table_schema = DATABASE() AND table_name = ? "
                             + "AND index_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static boolean fulltextIndexExists(String table, String index) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.statistics "
                             + "WHERE table_schema = DATABASE() AND table_name = ? "
                             + "AND index_name = ? AND index_type = 'FULLTEXT'")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    // ==================== 测试 ====================

    @Test
    @DisplayName("空库执行 RAG migrate: V2 执行成功, 历史表名为 rag_schema_history")
    void migrate_cleanDatabase_appliesRagMigrations() throws Exception {
        assertNotNull(firstMigrate, "migrate 应已执行");

        assertAll(
                () -> assertTrue(firstMigrate.success, "migrate 结果应为 success"),
                () -> assertEquals(1, firstMigrate.migrationsExecuted,
                        "rag-migration 目录下只有 V2, 应执行 1 个迁移"),
                () -> assertEquals(List.of("2"), appliedVersions(),
                        "rag_schema_history 中应有的版本只有 V2"),
                () -> assertTrue(tableExists(HISTORY_TABLE),
                        "历史表应为 rag_schema_history 而非默认 flyway_schema_history"),
                () -> assertFalse(tableExists("flyway_schema_history"),
                        "不应产生默认名的 flyway_schema_history, 否则两个模块会互相污染")
        );
    }

    @Test
    @DisplayName("V2 建出 knowledge_doc 与 knowledge_chunk 表")
    void migrate_createsKnowledgeTables() throws Exception {
        assertAll(
                () -> assertTrue(tableExists("knowledge_doc"), "knowledge_doc 表应存在"),
                () -> assertTrue(tableExists("knowledge_chunk"), "knowledge_chunk 表应存在")
        );
    }

    @Test
    @DisplayName("knowledge_* 的关键列与索引到位(含 ngram 全文索引)")
    void migrate_knowledgeSchemaMatchesDesign() throws Exception {
        assertAll(
                // knowledge_doc
                () -> assertEquals("varchar(32)", columnType("knowledge_doc", "id"),
                        "knowledge_doc.id 应为 VARCHAR(32) (ASSIGN_UUID)"),
                () -> assertEquals("varchar(32)", columnType("knowledge_doc", "source_type"),
                        "knowledge_doc.source_type 应存在"),
                () -> assertEquals("varchar(16)", columnType("knowledge_doc", "status"),
                        "knowledge_doc.status 应存在"),
                () -> assertTrue(indexExists("knowledge_doc", "idx_doc_status"),
                        "应建立 idx_doc_status"),
                // knowledge_chunk
                () -> assertEquals("varchar(32)", columnType("knowledge_chunk", "doc_id"),
                        "knowledge_chunk.doc_id 应存在"),
                () -> assertEquals("text", columnType("knowledge_chunk", "content").toLowerCase(),
                        "knowledge_chunk.content 应为 TEXT"),
                () -> assertEquals("varchar(64)", columnType("knowledge_chunk", "destination"),
                        "knowledge_chunk.destination (metadata 过滤列) 应存在"),
                () -> assertTrue(indexExists("knowledge_chunk", "uk_chunk_doc_seq"),
                        "应建立唯一索引 uk_chunk_doc_seq"),
                () -> assertTrue(fulltextIndexExists("knowledge_chunk", "ft_chunk_content"),
                        "应建立 FULLTEXT 索引 ft_chunk_content (ngram 中文分词)")
        );
    }

    @Test
    @DisplayName("重复 migrate 是幂等的: 第二次执行 0 个迁移")
    void migrate_twice_isIdempotent() throws Exception {
        MigrateResult second = newFlyway().migrate();

        assertAll(
                () -> assertEquals(0, second.migrationsExecuted, "第二次 migrate 不应执行任何迁移"),
                () -> assertEquals(0, flyway.info().pending().length, "不应残留 pending 迁移"),
                () -> assertEquals(List.of("2"), appliedVersions(), "版本集合应保持只有 V2")
        );
    }
}
