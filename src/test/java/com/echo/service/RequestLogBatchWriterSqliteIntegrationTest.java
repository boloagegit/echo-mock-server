package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.repository.RequestLogCheckpointRepository;
import com.echo.repository.RequestLogRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(RequestLogBatchWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RequestLogBatchWriterSqliteIntegrationTest {

    private static final Path SQLITE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "echo-request-log-writer-" + UUID.randomUUID() + ".sqlite");

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + SQLITE_PATH);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    RequestLogBatchWriter writer;

    @Autowired
    RequestLogRepository requestLogs;

    @Autowired
    RequestLogCheckpointRepository checkpoints;

    @Test
    void sqliteCommitsLogAndCheckpointTogether() {
        RequestLog log = RequestLog.builder()
                .protocol(Protocol.HTTP)
                .method("POST")
                .endpoint("/sqlite")
                .matched(true)
                .responseTimeMs(8)
                .responseStatus(201)
                .requestTime(LocalDateTime.now())
                .build();

        writer.persist("spool-sqlite", 11L, List.of(log), 100);

        assertThat(requestLogs.findAll()).singleElement()
                .extracting(RequestLog::getEndpoint).isEqualTo("/sqlite");
        assertThat(checkpoints.findById("spool-sqlite")).get()
                .extracting(checkpoint -> checkpoint.getLastSequence()).isEqualTo(11L);
    }

    @AfterAll
    static void cleanupSqliteFiles() throws IOException {
        Files.deleteIfExists(Path.of(SQLITE_PATH + "-wal"));
        Files.deleteIfExists(Path.of(SQLITE_PATH + "-shm"));
        Files.deleteIfExists(SQLITE_PATH);
    }
}
