package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.support.SqliteTestDatabaseCleanupListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestExecutionListeners(listeners = SqliteTestDatabaseCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
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
                () -> "com.echo.config.EchoSqliteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired RequestLogRepository repository;
    @Autowired RequestLogSummaryQuery query;
    @Autowired DataSource dataSource;

    @BeforeEach
    void clearLogs() {
        repository.deleteAllInBatch();
    }

    @Test
    void protocolOnlyPagesKeepTimeAndIdOrderAcrossMatchedAndUnmatchedLogs() {
        LocalDateTime base = LocalDateTime.of(2026, 2, 1, 0, 0);
        List<RequestLog> logs = repository.saveAllAndFlush(List.of(
                log(Protocol.JMS, false, "QUEUE.1", base.plusSeconds(3)),
                log(Protocol.HTTP, true, "/newer", base.plusSeconds(10)),
                log(Protocol.JMS, true, "QUEUE.2", base.plusSeconds(1)),
                log(Protocol.JMS, true, "QUEUE.3", base.plusSeconds(3)),
                log(Protocol.JMS, false, "QUEUE.4", base.plusSeconds(2)),
                log(Protocol.JMS, true, "QUEUE.5", base.plusSeconds(2))));
        var filter = new RequestLogSummaryQuery.Filter(null, Protocol.JMS, null, null, null);

        var first = query.query(filter, 0, 2, "requestTime", false);
        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.rows()).extracting(RequestLogSummaryQuery.SummaryRow::id)
                .containsExactly(logs.get(3).getId(), logs.get(0).getId());
        assertThat(query.query(filter, 1, 2, "requestTime", false).rows())
                .extracting(RequestLogSummaryQuery.SummaryRow::id)
                .containsExactly(logs.get(5).getId(), logs.get(4).getId());
        assertThat(query.query(filter, 2, 2, "requestTime", false).rows())
                .extracting(RequestLogSummaryQuery.SummaryRow::id)
                .containsExactly(logs.get(2).getId());
        assertThat(query.query(filter, 0, 5, "requestTime", true).rows())
                .extracting(RequestLogSummaryQuery.SummaryRow::id)
                .containsExactly(logs.get(2).getId(), logs.get(4).getId(), logs.get(5).getId(),
                        logs.get(0).getId(), logs.get(3).getId());
    }

    @Test
    void protocolOnlyListUsesIndexOrderInsteadOfSortingTheMatchingRows() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String direction : List.of("ASC", "DESC")) {
            String plan = String.join("\n", jdbc.query(
                    "EXPLAIN QUERY PLAN SELECT id, protocol, request_time, request_body IS NOT NULL, "
                            + "response_body IS NOT NULL FROM request_log WHERE protocol = ? "
                            + "ORDER BY request_time " + direction + ", id " + direction + " LIMIT ? OFFSET ?",
                    (rs, i) -> rs.getString("detail"), "JMS", 50, 200));

            assertThat(plan).contains(RequestLog.PROTOCOL_TIME_INDEX).doesNotContain("TEMP B-TREE");
        }
    }

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
        assertThat(result.rows().get(0).faultType()).isEqualTo("EMPTY_RESPONSE");
        assertThat(result.rows().get(0).scenarioName()).isEqualTo("order-flow");
        assertThat(result.rows().get(0).diagnosticId()).isEqualTo("process-orders-3");
        var byTrace = query.query(new RequestLogSummaryQuery.Filter(null, null, null,
                "process-orders-3", null), 0, 10, "requestTime", false);
        assertThat(byTrace.rows()).singleElement().satisfies(row -> assertThat(row.endpoint()).isEqualTo("/orders/3"));
    }

    private RequestLog log(Protocol protocol, boolean matched,
                           String endpoint, LocalDateTime time) {
        boolean statefulFault = "/orders/3".equals(endpoint);
        return RequestLog.builder().protocol(protocol).endpoint(endpoint)
                .diagnosticId(statefulFault ? "process-orders-3" : null)
                .matched(matched).responseTimeMs(1).requestTime(time)
                .faultType(statefulFault ? "EMPTY_RESPONSE" : null)
                .scenarioName(statefulFault ? "order-flow" : null)
                .scenarioFromState(statefulFault ? "Started" : null)
                .scenarioToState(statefulFault ? "Paid" : null)
                .build();
    }
}
