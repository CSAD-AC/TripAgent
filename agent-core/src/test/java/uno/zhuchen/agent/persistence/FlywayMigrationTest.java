package uno.zhuchen.agent.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
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
 * Flyway 迁移实跑测试: 在容器化的干净 MySQL 8.4 上执行 db/migration 的 V1~V8.
 *
 * <p>目的: 迁移脚本此前从未在"空库"上验证过, 一旦脚本含语法错误或隐含依赖,
 * 只会在生产首次启动时才炸. 本测试把这一步前移到 CI.
 *
 * <p>不依赖开发者本机已装 MySQL; 也不要求本机开着 Docker --
 * {@code disabledWithoutDocker = true} 让无 Docker 的机器自动 SKIP 而不是 FAIL.
 *
 * <p>注意: 本测试**故意不加** {@code @Tag("integration")}, 因为主 CI 用
 * {@code -DexcludedGroups=integration} 跑, 加了 tag 就会被排除, 等于没测.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("agent-core Flyway 迁移: 在干净 MySQL 8.4 上从零跑通 V1~V8")
class FlywayMigrationTest {

    /** 与生产同版本镜像: mysql:8.4 */
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("trip_agent")
                    .withUsername("trip")
                    .withPassword("trip_pass");

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    /** 生产用的历史表名 (Flyway 默认值). */
    private static final String HISTORY_TABLE = "flyway_schema_history";

    private static Flyway flyway;
    private static MigrateResult firstMigrate;

    /** 与 application.yml 的 spring.flyway 配置对齐: locations + baseline-on-migrate. */
    private static Flyway newFlyway() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations(MIGRATION_LOCATION)
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

    private static int columnCount(String table) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
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

    /** V6: created_at 同时要求 NOT NULL 且有 DEFAULT 兜底. */
    private static boolean createdAtHasDefault() throws SQLException {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT column_default, is_nullable FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = 'message' "
                             + "AND column_name = 'created_at'")) {
            if (!rs.next()) {
                return false;
            }
            return rs.getString("column_default") != null
                    && "NO".equalsIgnoreCase(rs.getString("is_nullable"));
        }
    }

    // ==================== 测试 ====================

    @Test
    @DisplayName("空库执行 migrate: V1~V8 全部执行且无异常")
    void migrate_cleanDatabase_appliesAllMigrations() throws Exception {
        assertNotNull(firstMigrate, "migrate 应已执行");

        assertAll(
                () -> assertTrue(firstMigrate.success, "migrate 结果应为 success"),
                () -> assertEquals(8, firstMigrate.migrationsExecuted,
                        "应恰好执行 8 个版本化迁移(V1~V8)"),
                () -> assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8"),
                        appliedVersions(),
                        "flyway_schema_history 中 success=1 的版本应恰好是 V1..V8")
        );
    }

    @Test
    @DisplayName("空库不应被 baselineOnMigrate 误判为已有库(否则 V1 会被跳过)")
    void migrate_baselineOnMigrate_doesNotSkipV1() throws Exception {
        boolean hasBaselineRow;
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM " + HISTORY_TABLE + " WHERE type = 'BASELINE'")) {
            rs.next();
            hasBaselineRow = rs.getInt(1) > 0;
        }

        String v1Type;
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT type FROM " + HISTORY_TABLE + " WHERE version = '1'")) {
            v1Type = rs.next() ? rs.getString(1) : null;
        }

        assertAll(
                () -> assertFalse(hasBaselineRow,
                        "空库不应产生 BASELINE 记录, 否则说明 baseline 逻辑误触发"),
                () -> assertEquals("SQL", v1Type,
                        "V1 应以 SQL 迁移(而非 baseline)的形式被应用")
        );
    }

    @Test
    @DisplayName("V1/V2 建出 conversation 与 message 表")
    void migrate_createsCoreTables() throws Exception {
        assertAll(
                () -> assertTrue(tableExists("conversation"), "conversation 表应存在"),
                () -> assertTrue(tableExists("message"), "message 表应存在")
        );
    }

    @Test
    @DisplayName("演进列到位: V3 metadata / V5 trace_id 扩容 / V6 created_at 默认值 / V7 token 拆分 / V8 summary")
    void migrate_evolvedColumnsExist() throws Exception {
        assertAll(
                // V3: message.metadata (TEXT)
                () -> assertEquals("text", columnType("message", "metadata").toLowerCase(),
                        "V3 应新增 message.metadata 列 (TEXT)"),
                // V7: 计费 token 拆分
                () -> assertEquals("int", columnType("message", "prompt_tokens"),
                        "V7 应新增 message.prompt_tokens (INT)"),
                () -> assertEquals("int", columnType("message", "completion_tokens"),
                        "V7 应新增 message.completion_tokens (INT)"),
                // V5: trace_id 由 8 位放大到 16 位
                () -> assertEquals("varchar(16)", columnType("message", "trace_id"),
                        "V5 应把 message.trace_id 放大到 VARCHAR(16)"),
                // V6: created_at 有 DEFAULT 兜底
                () -> assertTrue(createdAtHasDefault(),
                        "V6 应给 message.created_at 加 DEFAULT CURRENT_TIMESTAMP(3)"),
                // V8: conversation.summary
                () -> assertEquals("text", columnType("conversation", "summary").toLowerCase(),
                        "V8 应新增 conversation.summary (TEXT)"),
                // V4: 唯一约束 uk_conv_seq
                () -> assertTrue(indexExists("message", "uk_conv_seq"),
                        "V4 应建立唯一索引 uk_conv_seq"),
                () -> assertEquals(12, columnCount("message"),
                        "message 列数应为 V2(9) + V3(1) + V7(2) = 12, 覆盖全部演进列")
        );
    }

    @Test
    @DisplayName("重复 migrate 是幂等的: 第二次执行 0 个迁移且版本集合不变")
    void migrate_twice_isIdempotent() throws Exception {
        List<String> before = appliedVersions();
        MigrateResult second = newFlyway().migrate();
        List<String> after = appliedVersions();

        MigrationInfo[] applied = flyway.info().applied();

        assertAll(
                () -> assertEquals(0, second.migrationsExecuted,
                        "第二次 migrate 不应执行任何迁移(幂等)"),
                () -> assertEquals(8, applied.length, "flyway info 应显示 8 个已应用迁移"),
                () -> assertEquals(0, flyway.info().pending().length, "不应残留 pending 迁移"),
                () -> assertEquals(before, after, "重复迁移不应改变已应用版本集合")
        );
    }
}
