package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.RequestLogBatchWriter;
import com.echo.service.RequestLogService;
import com.echo.service.RequestLogUnavailableException;
import com.echo.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class RequestLogConsumerSessionTest {
    @TempDir Path directory;

    @Test
    void readsCountsAndCleanupReuseOneConnectionAndCloseItOnExit() throws Exception {
        var spool = spool();
        spool.start();
        try {
            spool.append(task("one"));
            Connection held;
            try (var reader = spool.openConsumerSession()) {
                var batch = reader.readBatchAfter(0, 10, 1_000_000);
                long sequence = batch.entries().get(0).sequence();
                held = connection(reader);
                assertThat(held.getAutoCommit()).isTrue();
                assertThat(reader.countBetween(0, sequence)).isEqualTo(1);
                assertThat(reader.sequenceBeforeNewest(0)).isEqualTo(sequence);
                reader.deleteThrough(sequence);
                assertThat(reader.readBatchAfter(0, 10, 1_000_000).entries()).isEmpty();
                assertThat(spool.pendingBytes()).isZero();
                assertThat(spool.pendingItems()).isZero();
                assertThat(connection(reader)).isSameAs(held);
                assertThat(held.isClosed()).isFalse();
            }
            assertThat(held.isClosed()).isTrue();
        } finally {
            spool.stop();
        }
    }

    @Test
    void idleConnectionDoesNotPinSnapshotOrBlockCheckpointAndSeesLaterAppends() throws Exception {
        var spool = spool();
        spool.start();
        try (var reader = spool.openConsumerSession()) {
            assertThat(reader.readBatchAfter(0, 10, 1_000_000).entries()).isEmpty();
            spool.append(task("later"));
            var entry = reader.readBatchAfter(0, 10, 1_000_000).entries().get(0);
            assertThat(entry.task().getEndpoint()).isEqualTo("/later");
            try (var external = DriverManager.getConnection(url()); var sql = external.createStatement();
                 var result = sql.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).as("no idle reader blocks checkpoint").isZero();
            }
            reader.deleteThrough(entry.sequence());
            assertThat(reader.countBetween(0, Long.MAX_VALUE)).isZero();
        } finally {
            spool.stop();
        }
    }

    @Test
    void busyDeleteKeepsRowsAndReservationsThenReconnectsForRetry() throws Exception {
        var spool = spool();
        spool.start();
        try (var reader = spool.openConsumerSession()) {
            spool.append(task("retry"));
            long sequence = reader.readBatchAfter(0, 10, 1_000_000).entries().get(0).sequence();
            Connection held = connection(reader);
            try (var sql = held.createStatement()) { sql.execute("PRAGMA busy_timeout=20"); }
            long reserved = spool.pendingBytes();
            try (var blocker = DriverManager.getConnection(url()); var sql = blocker.createStatement()) {
                sql.execute("BEGIN IMMEDIATE");
                try {
                    assertThatThrownBy(() -> reader.deleteThrough(sequence))
                            .isInstanceOf(RequestLogUnavailableException.class)
                            .hasMessageContaining("Cannot clean request-log spool");
                    assertThat(held.isClosed()).isTrue();
                    assertThat(spool.pendingBytes()).isEqualTo(reserved);
                    assertThat(spool.pendingItems()).isEqualTo(1);
                } finally { sql.execute("ROLLBACK"); }
            }
            assertThat(reader.readBatchAfter(0, 10, 1_000_000).entries())
                    .extracting(e -> e.task().getEndpoint()).containsExactly("/retry");
            assertThat(connection(reader)).isNotSameAs(held);
            reader.deleteThrough(sequence);
            assertThat(spool.pendingBytes()).isZero();
            assertThat(spool.pendingItems()).isZero();
        } finally {
            spool.stop();
        }
    }

    @Test
    void externallyClosedHandleIsReplacedWithoutLosingRows() throws Exception {
        var spool = spool();
        spool.start();
        try (var reader = spool.openConsumerSession()) {
            reader.readBatchAfter(0, 10, 1_000_000);
            Connection old = connection(reader);
            old.close();
            spool.append(task("reconnected"));
            assertThat(reader.readBatchAfter(0, 10, 1_000_000).entries())
                    .extracting(e -> e.task().getEndpoint()).containsExactly("/reconnected");
            assertThat(connection(reader)).isNotSameAs(old);
        } finally {
            spool.stop();
        }
    }

    @Test
    void corruptHeadClassificationAndDurableRowsSurviveConnectionInvalidation() throws Exception {
        var spool = spool();
        spool.start();
        try (var reader = spool.openConsumerSession()) {
            spool.append(task("corrupt"));
            long sequence = reader.readBatchAfter(0, 10, 1_000_000).entries().get(0).sequence();
            Connection old = connection(reader);
            try (var external = DriverManager.getConnection(url());
                 var sql = external.prepareStatement("UPDATE request_log_spool SET payload=? WHERE sequence_id=?")) {
                sql.setBytes(1, new byte[]{'{'});sql.setLong(2, sequence);sql.executeUpdate();
            }
            assertThatThrownBy(() -> reader.readBatchAfter(0, 10, 1_000_000))
                    .isInstanceOfSatisfying(RequestLogSpool.UnrecoverableSpoolEntryException.class, e -> {
                        assertThat(e.sequence()).isEqualTo(sequence);
                        assertThat(e.kind()).isEqualTo(RequestLogSpool.UnrecoverableSpoolEntryException.Kind.CORRUPT);
                    });
            assertThat(old.isClosed()).isTrue();
            assertThat(reader.countBetween(0, Long.MAX_VALUE)).isEqualTo(1);
            assertThat(spool.pendingItems()).isEqualTo(1);
        } finally {
            spool.stop();
        }
    }

    @Test
    void sessionRejectsCrossThreadUseAndUseAfterClose() throws Exception {
        var spool = spool();
        spool.start();
        try (var reader = spool.openConsumerSession()) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var result = executor.submit(() -> reader.readBatchAfter(0, 10, 1_000_000));
                assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class);
            } finally { executor.shutdownNow(); }
            assertThat(reader.readBatchAfter(0, 10, 1_000_000).entries()).isEmpty();
            reader.close();
            assertThatThrownBy(() -> reader.readBatchAfter(0, 10, 1_000_000))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        } finally { spool.stop(); }
    }

    @Test
    void agentShutdownClosesItsConsumerConnectionAndAllowsRestart() throws Exception {
        AtomicReference<RequestLogSpool.ConsumerSession> session = new AtomicReference<>();
        var spool = new RequestLogSpool(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("spool.sqlite").toString(), 128, 16, 1, 2_000, 25, 64 * 1024 * 1024) {
            @Override public ConsumerSession openConsumerSession() {
                var result = super.openConsumerSession();session.set(result);return result;
            }
        };
        spool.start();
        var config = mock(SystemConfigService.class);
        when(config.getRequestLogMaxRecords()).thenReturn(1000);
        var writer = mock(RequestLogBatchWriter.class);
        var checkpoint = new AtomicLong();
        when(writer.findCheckpoint(anyString())).thenAnswer(i -> checkpoint.get());
        var agent = new LogAgent(mock(RequestLogRepository.class), config, new ConditionMatcher(),
                mock(RequestLogService.class), spool, writer, 128, 50, 1, true, 10_000);
        try {
            for (int iteration = 0; iteration < 2; iteration++) {
                var persisted = new CountDownLatch(1);
                doAnswer(i -> {
                    checkpoint.set(i.getArgument(1));persisted.countDown();return null;
                }).when(writer).persist(anyString(), anyLong(), anyList(), anyInt());
                spool.append(task("cycle-" + iteration));
                agent.init();
                assertThat(persisted.await(3, TimeUnit.SECONDS)).isTrue();
                Connection held = connection(session.get());
                agent.destroy();
                assertThat(held.isClosed()).isTrue();
                session.set(null);
            }
        } finally { agent.destroy();spool.stop(); }
    }

    private RequestLogSpool spool() {
        return new RequestLogSpool(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("spool.sqlite").toString(), 128, 16, 1, 2_000, 25, 64 * 1024 * 1024);
    }

    private String url() { return "jdbc:sqlite:" + directory.resolve("spool.sqlite"); }

    private static Connection connection(RequestLogSpool.ConsumerSession reader) {
        return (Connection) ReflectionTestUtils.getField(reader, "connection");
    }

    private static LogTask task(String id) {
        return LogTask.builder().protocol(Protocol.HTTP).endpoint("/" + id)
                .requestTime(LocalDateTime.now()).requestBody("{\"id\":\"" + id + "\"}")
                .responseBody("ok").build();
    }
}
