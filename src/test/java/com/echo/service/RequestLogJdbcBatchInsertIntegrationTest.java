package com.echo.service;

import com.echo.config.SqliteTransactionConfiguration;
import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.repository.RequestLogCheckpointRepository;
import com.echo.repository.RequestLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false, properties = {"echo.request-log.jdbc-batch-enabled=true",
        "spring.jpa.properties.hibernate.jdbc.time_zone=Asia/Taipei"})
@Import({RequestLogBatchWriter.class, RequestLogJdbcBatchInsert.class, SqliteTransactionConfiguration.class,
        PersistenceExceptionTranslationAutoConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RequestLogJdbcBatchInsertIntegrationTest {
    @Autowired RequestLogBatchWriter writer;
    @Autowired RequestLogRepository logs;
    @Autowired RequestLogCheckpointRepository checkpoints;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clearTables() {
        logs.deleteAllInBatch();
        checkpoints.deleteAllInBatch();
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void allFieldsMatchJpaIncludingNormalizationAndTimestampQueries(Protocol protocol) {
        RequestLog jpa = logs.saveAndFlush(completeLog(protocol));
        RequestLog expected = logs.findById(jpa.getId()).orElseThrow();
        writer.persist("parity", 1, List.of(completeLog(protocol)), 100);
        List<RequestLog> stored = logs.findAll();
        assertThat(stored).hasSize(2);
        RequestLog actual = stored.stream().filter(row -> !row.getId().equals(jpa.getId())).findFirst().orElseThrow();
        assertThat(actual).usingRecursiveComparison().ignoringFields("id").isEqualTo(expected);
        assertThat(logs.countTodayRequests(expected.getRequestTime().minusSeconds(1))).isEqualTo(2);
        assertThat(logs.countTodayRequests(expected.getRequestTime().plusSeconds(1))).isZero();
        assertThat(writer.findCheckpoint("parity")).isEqualTo(1);
    }

    @Test
    void nullableFieldsAndDefaultTimestampRetainJpaSemantics() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        RequestLog log = RequestLog.builder().protocol(Protocol.JMS).endpoint("nullable").forwarded(null).build();
        writer.persist("nullable", 1, List.of(log), 100);
        assertThat(logs.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getForwarded()).isNull();
            assertThat(saved.getRequestBody()).isNull();
            assertThat(saved.getMethod()).isNull();
            assertThat(saved.getRequestTime()).isBetween(before, LocalDateTime.now().plusSeconds(1));
        });
    }

    @Test
    void partialBatchFailureRollsBackEveryRowAndPreservesPreviousCheckpoint() {
        writer.persist("rollback", 7, List.of(), 100);
        assertThatThrownBy(() -> writer.persist("rollback", 9,
                List.of(log("valid", 1), log(null, 2)), 100))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(logs.count()).isZero();
        assertThat(writer.findCheckpoint("rollback")).isEqualTo(7);
        writer.persist("rollback", 9, List.of(log("valid", 1), log("recovered", 2)), 100);
        assertThat(logs.count()).isEqualTo(2);
        assertThat(writer.findCheckpoint("rollback")).isEqualTo(9);
    }

    @Test
    void checkpointFailureRollsBackJdbcRowsAndLaterRetrySucceeds() {
        jdbc.execute("CREATE TRIGGER reject_test_checkpoint BEFORE INSERT ON request_log_checkpoint "
                + "WHEN NEW.spool_id = 'checkpoint-failure' BEGIN SELECT RAISE(ABORT, 'test checkpoint failure'); END");
        try {
            assertThatThrownBy(() -> writer.persist("checkpoint-failure", 1, List.of(log("rollback", 1)), 100))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(logs.count()).isZero();
            assertThat(checkpoints.findById("checkpoint-failure")).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_checkpoint");
        }
        writer.persist("checkpoint-failure", 1, List.of(log("recovered", 1)), 100);
        assertThat(logs.count()).isEqualTo(1);
        assertThat(writer.findCheckpoint("checkpoint-failure")).isEqualTo(1);
    }

    @Test
    void joinsOuterTransactionAndRollsBackWithIt() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            writer.persist("outer", 2, List.of(log("one", 1), log("two", 2)), 100);
            throw new IllegalStateException("rollback outer transaction");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(logs.count()).isZero();
        assertThat(checkpoints.findById("outer")).isEmpty();
    }

    @Test
    void retentionKeepsNewestRowsWhileCheckpointAdvances() {
        writer.persist("retention", 9, IntStream.range(0, 9).mapToObj(i -> log("row-" + i, i)).toList(), 10);
        writer.persist("retention", 12, IntStream.range(9, 12).mapToObj(i -> log("row-" + i, i)).toList(), 10);
        assertThat(logs.findAll()).extracting(RequestLog::getEndpoint)
                .containsExactlyInAnyOrder("row-5", "row-6", "row-7", "row-8", "row-9", "row-10", "row-11");
        assertThat(writer.findCheckpoint("retention")).isEqualTo(12);
    }

    @Test
    void existingIdsKeepJpaUpdateBehavior() {
        RequestLog existing = logs.saveAndFlush(log("original", 1));
        existing.setEndpoint("updated");
        writer.persist("existing", 1, List.of(existing), 100);
        assertThat(logs.findAll()).singleElement().extracting(RequestLog::getEndpoint).isEqualTo("updated");
    }

    private static RequestLog log(String endpoint, int second) {
        return RequestLog.builder().protocol(Protocol.HTTP).method("GET").endpoint(endpoint)
                .requestTime(LocalDateTime.of(2026, 10, 18, 12, 0).plusSeconds(second)).build();
    }

    private static RequestLog completeLog(Protocol protocol) {
        return RequestLog.builder().protocol(protocol).diagnosticId("d\0" + "x".repeat(40))
                .ruleId("r".repeat(40)).method("METHOD-LONGER-THAN-TEN").endpoint("e".repeat(499) + "😀tail")
                .matched(true).responseTimeMs(123).matchTimeMs(4).clientIp("c".repeat(60))
                .targetHost("h".repeat(260)).forwarded(true).forwardTarget("t".repeat(1100))
                .proxyStatus(502).proxyError("p".repeat(260)).responseStatus(201)
                .requestBody("請求\0" + "a".repeat(16384)).responseBody("回應\0" + "b".repeat(16384))
                .matchChain("[{\"reason\":\"x\0y\"}]").faultType("f".repeat(25))
                .scenarioName("s".repeat(110)).scenarioFromState("a".repeat(110)).scenarioToState("b".repeat(110))
                .candidateCount(3).matchedByFallback(false).delayMs(10).sseEventCount(2)
                .scenarioSkipped("skip\0reason")
                .requestTime(LocalDateTime.of(2026, 10, 18, 12, 34, 56, 123456789)).build();
    }
}
