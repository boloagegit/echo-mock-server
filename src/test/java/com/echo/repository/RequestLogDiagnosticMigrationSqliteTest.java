package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

/** Existing SQLite rows and schema must upgrade without losing historical logs. */
@DataJpaTest
class RequestLogDiagnosticMigrationSqliteTest {
    private static final Path DB = createLegacyDatabase();
    private static Path createLegacyDatabase() {
        try {
            Path path = Files.createTempFile("echo-diagnostic-migration-", ".sqlite");
            path.toFile().deleteOnExit();
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE request_log (
                      id INTEGER PRIMARY KEY, protocol VARCHAR(255) NOT NULL, endpoint VARCHAR(500) NOT NULL,
                      matched BOOLEAN NOT NULL, response_time_ms INTEGER NOT NULL, request_time TIMESTAMP NOT NULL,
                      rule_id VARCHAR(36), method VARCHAR(10), match_time_ms INTEGER, client_ip VARCHAR(50),
                      match_chain TEXT, target_host VARCHAR(255), forwarded BOOLEAN, forward_target VARCHAR(1000),
                      proxy_status INTEGER, proxy_error VARCHAR(255), response_status INTEGER,
                      request_body TEXT, response_body TEXT, fault_type VARCHAR(20), scenario_name VARCHAR(100),
                      scenario_from_state VARCHAR(100), scenario_to_state VARCHAR(100))
                    """);
                statement.execute("INSERT INTO request_log(id,protocol,endpoint,matched,response_time_ms,request_time) "
                        + "VALUES(1,'HTTP','/historical',1,1,'2026-09-30 12:00:00.000')");
            }
            return path;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }
    @Autowired RequestLogRepository repository;
    @Test
    void oldRowsRemainReadableAndNewIdPersistsAfterNullableColumnIsAdded() {
        assertThat(repository.findById(1L)).hasValueSatisfying(row -> {
            assertThat(row.getEndpoint()).isEqualTo("/historical");
            assertThat(row.getDiagnosticId()).isNull();
        });
        RequestLog saved = repository.saveAndFlush(RequestLog.builder().protocol(Protocol.JMS)
                .endpoint("DEMO.REQUEST").requestTime(LocalDateTime.now()).responseTimeMs(1)
                .diagnosticId("process-123").build());
        assertThat(repository.findById(saved.getId())).hasValueSatisfying(row ->
                assertThat(row.getDiagnosticId()).isEqualTo("process-123"));
    }
}
