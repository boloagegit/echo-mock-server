package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
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
@Import(RequestLogSummaryQuery.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RequestLogSummaryQuerySqliteIntegrationTest {

    private static final Path SQLITE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "echo-request-log-query-" + UUID.randomUUID() + ".sqlite");

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + SQLITE_PATH);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired RequestLogRepository repository;
    @Autowired RequestLogSummaryQuery query;

    @Test
    void sqliteSupportsDynamicFilteringProjectionAndPaging() {
        LocalDateTime base = LocalDateTime.of(2026, 2, 1, 0, 0);
        repository.saveAllAndFlush(List.of(
                log(Protocol.HTTP, true, "/orders/1", base.plusSeconds(1)),
                log(Protocol.HTTP, false, "/orders/2", base.plusSeconds(2)),
                log(Protocol.JMS, true, "ORDER.Q", base.plusSeconds(3)),
                log(Protocol.HTTP, true, "/orders/3", base.plusSeconds(4))));

        var result = query.query(
                new RequestLogSummaryQuery.Filter(null, Protocol.HTTP, true, "orders", null),
                0, 10, "requestTime", false);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.rows()).extracting(RequestLogSummaryQuery.SummaryRow::endpoint)
                .containsExactly("/orders/3", "/orders/1");
    }

    private RequestLog log(Protocol protocol, boolean matched,
                           String endpoint, LocalDateTime time) {
        return RequestLog.builder().protocol(protocol).endpoint(endpoint)
                .matched(matched).responseTimeMs(1).requestTime(time).build();
    }

    @AfterAll
    static void cleanupSqliteFiles() throws IOException {
        Files.deleteIfExists(Path.of(SQLITE_PATH + "-wal"));
        Files.deleteIfExists(Path.of(SQLITE_PATH + "-shm"));
        Files.deleteIfExists(SQLITE_PATH);
    }
}
