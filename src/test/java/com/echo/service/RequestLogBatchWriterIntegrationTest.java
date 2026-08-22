package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.repository.RequestLogCheckpointRepository;
import com.echo.repository.RequestLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(RequestLogBatchWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RequestLogBatchWriterIntegrationTest {

    @Autowired
    RequestLogBatchWriter writer;

    @Autowired
    RequestLogRepository requestLogs;

    @Autowired
    RequestLogCheckpointRepository checkpoints;

    @BeforeEach
    void clearTables() {
        requestLogs.deleteAllInBatch();
        checkpoints.deleteAllInBatch();
    }

    @Test
    void h2CommitsLogAndCheckpointTogether() {
        writer.persist("spool-h2", 7L, List.of(validLog("/h2")), 100);

        assertThat(requestLogs.findAll()).singleElement().satisfies(saved -> {
            assertThat(saved.getEndpoint()).isEqualTo("/h2");
            assertThat(saved.isForwarded()).isTrue();
            assertThat(saved.getForwardTarget())
                    .isEqualTo("Primary HTTP | https://downstream.example");
        });
        assertThat(writer.findCheckpoint("spool-h2")).isEqualTo(7L);
    }

    @Test
    void h2RollsBackCheckpointWhenLogBatchFails() {
        RequestLog invalid = validLog(null);

        assertThatThrownBy(() -> writer.persist("spool-rollback", 9L,
                List.of(invalid), 100))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(requestLogs.count()).isZero();
        assertThat(checkpoints.findById("spool-rollback")).isEmpty();
    }

    @Test
    void h2TruncatesOversizedForwardTargetWithoutRollingBackBatch() {
        RequestLog log = validLog("/long-target");
        log.setForwardTarget("x".repeat(RequestLog.MAX_FORWARD_TARGET_LENGTH + 200));

        writer.persist("spool-long-target", 10L, List.of(log), 100);

        assertThat(requestLogs.findAll()).singleElement().satisfies(saved ->
                assertThat(saved.getForwardTarget())
                        .hasSize(RequestLog.MAX_FORWARD_TARGET_LENGTH));
        assertThat(writer.findCheckpoint("spool-long-target")).isEqualTo(10L);
    }

    private RequestLog validLog(String endpoint) {
        return RequestLog.builder()
                .protocol(Protocol.HTTP)
                .method("GET")
                .endpoint(endpoint)
                .matched(true)
                .forwarded(true)
                .forwardTarget("Primary HTTP | https://downstream.example")
                .responseTimeMs(5)
                .responseStatus(200)
                .requestTime(LocalDateTime.now())
                .build();
    }
}
