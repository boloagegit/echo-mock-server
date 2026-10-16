package com.echo.repository;

import com.echo.agent.LogAgent;
import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.service.RequestLogBatchWriter;
import com.echo.support.SqliteTestDatabaseCleanupListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 實際 SQLite：被資料庫拒絕的紀錄會被辨識為「資料本身的問題」，背景寫入只會跳過那一筆。 */
@DataJpaTest
@TestExecutionListeners(listeners = SqliteTestDatabaseCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Import(RequestLogBatchWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RequestLogRejectedRowSqliteTest {

    private static final Path SQLITE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "echo-request-log-rejected-" + UUID.randomUUID() + ".sqlite");

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + SQLITE_PATH);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "com.echo.config.EchoSqliteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired RequestLogBatchWriter writer;
    @Autowired RequestLogRepository repository;

    @Test
    void constraintFailureIsRecognisedAsRejectedDataAndNothingIsCommitted() {
        repository.deleteAllInBatch();
        RequestLog missingEndpoint = RequestLog.builder().protocol(Protocol.HTTP).method("GET")
                .matched(true).responseTimeMs(1).requestTime(LocalDateTime.now()).build();

        Throwable error = catchThrowable(() -> writer.persist("spool", 1, List.of(missingEndpoint), 1_000));

        assertThat(error).isNotNull();
        assertThat(LogAgent.isRejectedData(error)).isTrue();
        assertThat(writer.findCheckpoint("spool")).isZero();
        assertThat(repository.count()).isZero();

        // 跳過該筆：只推進進度
        writer.persist("spool", 1, List.of(), 1_000);
        assertThat(writer.findCheckpoint("spool")).isEqualTo(1);
    }

    @Test
    void longValuesFromRequestsAreStoredShortened() {
        repository.deleteAllInBatch();
        RequestLog log = RequestLog.builder().protocol(Protocol.HTTP).method("VERYLONGMETHOD")
                .endpoint("/" + "x".repeat(700)).targetHost("h".repeat(300)).matched(false)
                .responseTimeMs(1).requestTime(LocalDateTime.now()).requestBody("a\0b").build();

        writer.persist("spool-long", 1, List.of(log), 1_000);

        RequestLog stored = repository.findAll().stream()
                .filter(row -> row.getEndpoint().startsWith("/x")).findFirst().orElseThrow();
        assertThat(stored.getEndpoint()).hasSize(500);
        assertThat(stored.getMethod()).hasSize(10);
        assertThat(stored.getTargetHost()).hasSize(255);
        assertThat(stored.getRequestBody()).isEqualTo("ab");
    }
}
