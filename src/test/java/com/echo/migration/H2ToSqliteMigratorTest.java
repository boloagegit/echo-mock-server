package com.echo.migration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class H2ToSqliteMigratorTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadDrivers() throws ClassNotFoundException {
        Class.forName("org.h2.Driver");
        Class.forName("org.sqlite.JDBC");
    }

    @Test
    void preservesNullEmptyUnicodeMultilineAndLargeTextAndVerifiesHash() throws Exception {
        String h2Url = h2Url("values-source");
        String sqliteUrl = sqliteUrl("values-target.sqlite");
        LocalDateTime preciseTime = LocalDateTime.of(2026, 8, 8, 12, 34, 56, 123_456_789);
        String largeBody = "第一行,\"quoted\"\n第二行🙂\n" + "x".repeat(200_000);

        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "")) {
            h2.createStatement().execute("""
                    CREATE TABLE responses (
                        id BIGINT PRIMARY KEY,
                        version BIGINT,
                        description VARCHAR(255),
                        body CLOB,
                        body_size INTEGER,
                        content_type VARCHAR(20),
                        created_at TIMESTAMP,
                        updated_at TIMESTAMP,
                        extended_at TIMESTAMP
                    )
                    """);
            try (var insert = h2.prepareStatement("""
                    INSERT INTO responses
                    (id, version, description, body, body_size, content_type,
                     created_at, updated_at, extended_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setLong(1, 1);
                insert.setLong(2, 0);
                insert.setString(3, "");
                insert.setObject(4, null);
                insert.setInt(5, 0);
                insert.setString(6, "TEXT");
                insert.setTimestamp(7, Timestamp.valueOf(preciseTime));
                insert.setTimestamp(8, Timestamp.valueOf(preciseTime));
                insert.setObject(9, null);
                insert.executeUpdate();

                insert.setLong(1, 2);
                insert.setLong(2, 7);
                insert.setString(3, "中文🙂");
                insert.setString(4, largeBody);
                insert.setInt(5, largeBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                insert.setString(6, "TEXT");
                insert.setTimestamp(7, Timestamp.valueOf(preciseTime));
                insert.setTimestamp(8, Timestamp.valueOf(preciseTime));
                insert.setTimestamp(9, Timestamp.valueOf(preciseTime));
                insert.executeUpdate();
            }
        }

        try (Connection sqlite = DriverManager.getConnection(sqliteUrl)) {
            sqlite.createStatement().execute("""
                    CREATE TABLE responses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        version BIGINT,
                        description VARCHAR(255),
                        body CLOB,
                        body_size INTEGER,
                        content_type VARCHAR(20),
                        created_at TIMESTAMP,
                        updated_at TIMESTAMP,
                        extended_at TIMESTAMP
                    )
                    """);
        }

        var report = H2ToSqliteMigrator.migrate(
                h2Url, "sa", "", sqliteUrl, List.of("responses"));

        assertThat(report.totalRows()).isEqualTo(2);
        assertThat(report.tables()).singleElement().satisfies(table -> {
            assertThat(table.sourceRows()).isEqualTo(2);
            assertThat(table.targetRows()).isEqualTo(2);
            assertThat(table.sourceSha256()).isEqualTo(table.targetSha256());
        });

        try (Connection sqlite = DriverManager.getConnection(sqliteUrl);
             var rows = sqlite.createStatement().executeQuery(
                     "SELECT * FROM responses ORDER BY id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("description")).isEmpty();
            assertThat(rows.getObject("body")).isNull();
            assertThat(rows.getTimestamp("created_at").getTime())
                    .isEqualTo(epochMillis(preciseTime));

            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("description")).isEqualTo("中文🙂");
            assertThat(rows.getString("body")).isEqualTo(largeBody);
            assertThat(rows.getTimestamp("extended_at").getTime())
                    .isEqualTo(epochMillis(preciseTime));
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void rollsBackAllTargetChangesWhenAnyInsertFails() throws Exception {
        String h2Url = h2Url("rollback-source");
        String sqliteUrl = sqliteUrl("rollback-target.sqlite");
        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "")) {
            h2.createStatement().execute(
                    "CREATE TABLE builtin_users (id BIGINT PRIMARY KEY, username VARCHAR(50))");
            h2.createStatement().execute(
                    "INSERT INTO builtin_users VALUES (1, 'duplicate'), (2, 'duplicate')");
        }
        try (Connection sqlite = DriverManager.getConnection(sqliteUrl)) {
            sqlite.createStatement().execute("""
                    CREATE TABLE builtin_users (
                        id INTEGER PRIMARY KEY,
                        username VARCHAR(50) NOT NULL UNIQUE
                    )
                    """);
            sqlite.createStatement().execute(
                    "INSERT INTO builtin_users VALUES (99, 'must-survive-rollback')");
        }

        assertThatThrownBy(() -> H2ToSqliteMigrator.migrate(
                h2Url, "sa", "", sqliteUrl, List.of("builtin_users")))
                .isInstanceOf(SQLException.class);

        try (Connection sqlite = DriverManager.getConnection(sqliteUrl);
             var rows = sqlite.createStatement().executeQuery(
                     "SELECT id, username FROM builtin_users")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong("id")).isEqualTo(99);
            assertThat(rows.getString("username")).isEqualTo("must-survive-rollback");
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void explicitlyReportsTheKnownLegacyRequestLogColumn() throws Exception {
        String h2Url = h2Url("legacy-source");
        String sqliteUrl = sqliteUrl("legacy-target.sqlite");
        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "")) {
            h2.createStatement().execute("""
                    CREATE TABLE request_log (
                        id BIGINT PRIMARY KEY,
                        endpoint VARCHAR(500),
                        condition_matched BOOLEAN
                    )
                    """);
            h2.createStatement().execute("INSERT INTO request_log VALUES (1, '/legacy', TRUE)");
        }
        try (Connection sqlite = DriverManager.getConnection(sqliteUrl)) {
            sqlite.createStatement().execute("""
                    CREATE TABLE request_log (
                        id INTEGER PRIMARY KEY,
                        endpoint VARCHAR(500)
                    )
                    """);
        }

        var report = H2ToSqliteMigrator.migrate(
                h2Url, "sa", "", sqliteUrl, List.of("request_log"));

        assertThat(report.tables()).singleElement().satisfies(table ->
                assertThat(table.skippedLegacyColumns()).containsExactly("condition_matched"));
    }

    @Test
    void failsWhenTheSQLiteSchemaHasAColumnMissingFromH2() throws Exception {
        String h2Url = h2Url("schema-source");
        String sqliteUrl = sqliteUrl("schema-target.sqlite");
        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "")) {
            h2.createStatement().execute("CREATE TABLE responses (id BIGINT PRIMARY KEY)");
        }
        try (Connection sqlite = DriverManager.getConnection(sqliteUrl)) {
            sqlite.createStatement().execute(
                    "CREATE TABLE responses (id INTEGER PRIMARY KEY, body CLOB)");
        }

        assertThatThrownBy(() -> H2ToSqliteMigrator.migrate(
                h2Url, "sa", "", sqliteUrl, List.of("responses")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("SQLite column has no H2 source")
                .hasMessageContaining("responses.body");
    }

    @Test
    void migratesEncryptedJmsTargetProfiles() throws Exception {
        String h2Url = h2Url("jms-target-source");
        String sqliteUrl = sqliteUrl("jms-target.sqlite");
        createJmsTargetTable(h2Url, true);
        createJmsTargetTable(sqliteUrl, false);

        var report = H2ToSqliteMigrator.migrate(
                h2Url, "sa", "", sqliteUrl, List.of("jms_target_connections"));

        assertThat(report.totalRows()).isEqualTo(1);
        assertThat(report.tables()).singleElement().satisfies(table -> {
            assertThat(table.sourceRows()).isEqualTo(1);
            assertThat(table.sourceSha256()).isEqualTo(table.targetSha256());
        });
        try (Connection sqlite = DriverManager.getConnection(sqliteUrl);
             var row = sqlite.createStatement().executeQuery(
                     "SELECT name, encrypted_password, is_default "
                             + "FROM jms_target_connections")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("name")).isEqualTo("Primary JMS target");
            assertThat(row.getString("encrypted_password")).isEqualTo("v1:ciphertext");
            assertThat(row.getBoolean("is_default")).isTrue();
        }
    }

    @Test
    void treatsPreFeatureMissingJmsTargetTableAsEmpty() throws Exception {
        String h2Url = h2Url("old-source");
        String sqliteUrl = sqliteUrl("new-target.sqlite");
        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "")) {
            h2.createStatement().execute("CREATE TABLE responses (id BIGINT PRIMARY KEY)");
        }
        createJmsTargetTable(sqliteUrl, false);

        var report = H2ToSqliteMigrator.migrate(
                h2Url, "sa", "", sqliteUrl, List.of("jms_target_connections"));

        assertThat(report.totalRows()).isZero();
        assertThat(report.tables()).singleElement().satisfies(table -> {
            assertThat(table.sourceRows()).isZero();
            assertThat(table.targetRows()).isZero();
            assertThat(table.sourceSha256()).isEqualTo(table.targetSha256());
        });
    }

    private static void createJmsTargetTable(String jdbcUrl, boolean insert) throws Exception {
        try (Connection connection = jdbcUrl.startsWith("jdbc:h2:")
                ? DriverManager.getConnection(jdbcUrl, "sa", "")
                : DriverManager.getConnection(jdbcUrl)) {
            connection.createStatement().execute("""
                    CREATE TABLE jms_target_connections (
                        id BIGINT PRIMARY KEY,
                        version BIGINT,
                        name VARCHAR(100),
                        provider_type VARCHAR(20),
                        server_url VARCHAR(500),
                        username VARCHAR(200),
                        encrypted_password VARCHAR(2000),
                        queue_name VARCHAR(255),
                        timeout_seconds INTEGER,
                        enabled BOOLEAN,
                        is_default BOOLEAN,
                        created_at TIMESTAMP,
                        updated_at TIMESTAMP
                    )
                    """);
            if (insert) {
                connection.createStatement().execute("""
                        INSERT INTO jms_target_connections VALUES (
                            1, 0, 'Primary JMS target', 'artemis', 'tcp://localhost:61616',
                            'echo', 'v1:ciphertext', 'TARGET.REQUEST', 30, TRUE, TRUE,
                            TIMESTAMP '2026-08-08 12:00:00', TIMESTAMP '2026-08-08 12:00:00')
                        """);
            }
        }
    }

    private String h2Url(String name) {
        return "jdbc:h2:file:" + tempDir.resolve(name).toAbsolutePath();
    }

    private String sqliteUrl(String name) {
        return "jdbc:sqlite:" + tempDir.resolve(name).toAbsolutePath();
    }

    private static long epochMillis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
