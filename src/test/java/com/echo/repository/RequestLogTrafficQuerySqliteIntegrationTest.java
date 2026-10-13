package com.echo.repository;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({RequestLogTrafficQuery.class, RequestLogSummaryQuery.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RequestLogTrafficQuerySqliteIntegrationTest {

    private static final Path SQLITE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "echo-request-log-traffic-" + UUID.randomUUID() + ".sqlite");
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 12, 14, 0);

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + SQLITE_PATH);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "com.echo.config.EchoSqliteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired RequestLogRepository repository;
    @Autowired RequestLogTrafficQuery trafficQuery;
    @Autowired RequestLogSummaryQuery summaryQuery;
    @Autowired DataSource dataSource;

    @Test
    void readsOnlyRecordsInTheRangeAndGroupsTheUnmatchedOnes() {
        repository.deleteAllInBatch();
        repository.saveAllAndFlush(List.of(
                log(BASE.minusMinutes(90), true, false, null, "/old"),
                timed(log(BASE.plusMinutes(1), true, false, null, "/mock"), 1),
                log(BASE.plusMinutes(2), true, true, null, "/forward"),
                timed(log(BASE.plusMinutes(3), true, true, "connect timed out", "/forward"), 3000),
                log(BASE.plusMinutes(4), false, false, null, "/refund"),
                log(BASE.plusMinutes(5), false, false, null, "/refund"),
                log(BASE.plusMinutes(6), false, false, null, "/avatar")));

        // 兩格：14:00–14:03（不含）與 14:03 之後；14:00 以前的紀錄不算
        var buckets = trafficQuery.countByBucket(List.of(BASE, BASE.plusMinutes(3)));

        assertThat(buckets).containsExactlyInAnyOrder(
                new RequestLogTrafficQuery.BucketCount(0, 2, 0, 1, 0),
                new RequestLogTrafficQuery.BucketCount(1, 4, 3, 0, 1));
        assertThat(trafficQuery.countByResponseTime(BASE, new int[] {2, 10})).containsExactly(1, 4, 1);
        // 只算到概況產生的時間（不含）：14:06 那筆 /avatar 不算
        assertThat(trafficQuery.topUnmatched(BASE, BASE.plusMinutes(6), 5)).containsExactly(
                new RequestLogTrafficQuery.EndpointCount(Protocol.HTTP, "GET", "/refund", 2));
        assertThat(trafficQuery.topUnmatched(BASE, BASE.plusHours(1), 5)).containsExactly(
                new RequestLogTrafficQuery.EndpointCount(Protocol.HTTP, "GET", "/refund", 2),
                new RequestLogTrafficQuery.EndpointCount(Protocol.HTTP, "GET", "/avatar", 1));
        assertThat(trafficQuery.earliestRequestTime()).isEqualTo(BASE.minusMinutes(90));
    }

    @Test
    void deletingTheOldestRecordsRaisesTheSmallestId() {
        repository.deleteAllInBatch();
        assertThat(trafficQuery.smallestId()).isNull();
        List<RequestLog> saved = repository.saveAllAndFlush(List.of(
                log(BASE, true, false, null, "/a"), log(BASE.plusMinutes(1), true, false, null, "/b"),
                log(BASE.plusMinutes(2), true, false, null, "/c")));
        assertThat(trafficQuery.smallestId()).isEqualTo(saved.get(0).getId());

        // 與筆數上限相同的刪法：刪掉最舊的
        repository.deleteOldest(2);

        assertThat(trafficQuery.smallestId()).isEqualTo(saved.get(2).getId());
    }

    @Test
    void listQueriesCanBeLimitedToATimeRange() {
        repository.deleteAllInBatch();
        repository.saveAllAndFlush(List.of(
                log(BASE.minusSeconds(1), true, false, null, "/before"),
                log(BASE, true, false, null, "/start"),
                log(BASE.plusMinutes(29), true, false, null, "/inside"),
                log(BASE.plusMinutes(30), true, false, null, "/end")));
        LocalDateTime to = BASE.plusMinutes(30);

        var criteria = summaryQuery.query(new RequestLogSummaryQuery.Filter(null, null, null, null, null, BASE, to),
                0, 10, "requestTime", true);
        var jpql = repository.findSummaryPage(null, null, null, null, null, BASE, to,
                PageRequest.of(0, 10, Sort.by("requestTime")));

        assertThat(criteria.rows()).extracting(RequestLogSummaryQuery.SummaryRow::endpoint)
                .containsExactly("/start", "/inside");
        assertThat(jpql.getContent()).extracting(row -> (String) row[4]).containsExactly("/start", "/inside");
    }

    @Test
    void theChartQueriesAreAnsweredFromTheCoveringIndex() {
        // 與 RequestLogTrafficQuery 產生的 SQL 相同的欄位與條件
        String plan = String.join("\n", new JdbcTemplate(dataSource).query(
                "EXPLAIN QUERY PLAN SELECT CASE WHEN request_time < ? THEN 0 ELSE 1 END, COUNT(*), "
                        + "SUM(CASE WHEN matched = 0 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN matched = 1 AND proxy_error IS NULL AND forwarded = 1 THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN matched = 1 AND proxy_error IS NOT NULL THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN response_time_ms <= 5 THEN 1 ELSE 0 END) "
                        + "FROM request_log WHERE request_time >= ? GROUP BY 1",
                (rs, i) -> rs.getString("detail"), BASE.plusMinutes(3), BASE));

        assertThat(plan).contains("COVERING INDEX " + RequestLog.TRAFFIC_INDEX);
    }

    private static RequestLog log(LocalDateTime time, boolean matched, boolean forwarded, String proxyError,
                                  String endpoint) {
        return RequestLog.builder().protocol(Protocol.HTTP).method("GET").endpoint(endpoint)
                .matched(matched).forwarded(forwarded).proxyError(proxyError)
                .responseTimeMs(5).requestTime(time).requestBody("x".repeat(2000)).build();
    }

    private static RequestLog timed(RequestLog log, int responseTimeMs) {
        log.setResponseTimeMs(responseTimeMs);
        return log;
    }

    @AfterAll
    static void cleanupSqliteFiles() throws IOException {
        Files.deleteIfExists(Path.of(SQLITE_PATH + "-wal"));
        Files.deleteIfExists(Path.of(SQLITE_PATH + "-shm"));
        Files.deleteIfExists(SQLITE_PATH);
    }
}
