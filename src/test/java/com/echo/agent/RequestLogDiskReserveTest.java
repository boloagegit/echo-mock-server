package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.service.RequestLogUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class RequestLogDiskReserveTest {
    @TempDir Path directory;

    @Test
    void pausesBeforeWritingAndRecoversEvenWhenNoNewAppendsCanEnter() {
        var free = new AtomicLong(0);
        var clock = new AtomicLong();
        var reserve = new RequestLogDiskReserve(1024, free::get, clock::get);
        var spool = new RequestLogSpool(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("reserve.sqlite").toString(), 10, 10, 1, 1000, 25, 1024 * 1024);
        spool.setDiskReserve(reserve);
        spool.start();
        try {
            await().atMost(2, TimeUnit.SECONDS).until(spool::isStorageUnavailable);
            assertThatThrownBy(() -> spool.append(task("/blocked"))).isInstanceOf(RequestLogUnavailableException.class);
            assertThat(spool.readAfter(0, 10)).isEmpty();
            assertThat(spool.statusSnapshot().pendingItems()).isZero();
            free.set(4096);
            clock.set(TimeUnit.SECONDS.toNanos(2));
            await().atMost(2, TimeUnit.SECONDS).until(() -> !spool.isStorageUnavailable());
            spool.append(task("/recovered"));
            assertThat(spool.readAfter(0, 10)).extracting(entry -> entry.task().getEndpoint()).containsExactly("/recovered");
        } finally {
            spool.stop();
        }
    }

    @Test
    void lowReserveMustNotDeclareAnAlreadyAttemptedBatchUnwritten() {
        var free = new AtomicLong(4096);
        var clock = new AtomicLong();
        var reserve = new RequestLogDiskReserve(1024, free::get, clock::get);
        var spool = new RequestLogSpool(new ObjectMapper().findAndRegisterModules(),
                directory.resolve("retry-reserve.sqlite").toString(), 10, 10, 1, 1000, 25, 1024 * 1024);
        var attempts = new AtomicInteger();
        spool.setDiskReserve(reserve);
        spool.beforeCommitForTest = () -> {
            if (attempts.incrementAndGet() == 1) {
                free.set(0);
                clock.set(TimeUnit.SECONDS.toNanos(2));
                throw new java.sql.SQLException("write outcome must follow the existing retry policy");
            }
        };
        spool.start();
        try {
            // SQL failures are conservatively treated as uncertain. Low space must not
            // turn that outcome into a definitive "not written" signal to strict callers.
            spool.append(task("/attempted"));
            assertThat(attempts).hasValue(2);
            assertThat(spool.readAfter(0, 10)).extracting(entry -> entry.task().getEndpoint())
                    .containsExactly("/attempted");
            assertThat(spool.isStorageUnavailable()).isTrue();
            assertThatThrownBy(() -> spool.append(task("/new")))
                    .isInstanceOf(RequestLogUnavailableException.class);
            free.set(4096);
            clock.set(TimeUnit.SECONDS.toNanos(4));
            await().atMost(2, TimeUnit.SECONDS).until(() -> !spool.isStorageUnavailable());
            spool.append(task("/recovered"));
            assertThat(spool.readAfter(0, 10)).extracting(entry -> entry.task().getEndpoint())
                    .containsExactly("/attempted", "/recovered");
        } finally {
            spool.stop();
        }
    }

    @Test
    void cachesDiskChecksAndFailsClosedWhenSpaceCannotBeRead() {
        var calls = new AtomicInteger();
        var clock = new AtomicLong();
        var reserve = new RequestLogDiskReserve(1024, () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("probe unavailable");
            return 4096;
        }, clock::get);
        assertThat(reserve.refresh()).isFalse();
        for (int i = 0; i < 100; i++) {
            assertThat(reserve.blocked()).isTrue();
            assertThat(reserve.refresh()).isFalse();
            reserve.metrics();
        }
        assertThat(calls).hasValue(1);
        clock.set(TimeUnit.SECONDS.toNanos(2));
        assertThat(reserve.refresh()).isTrue();
        assertThat(reserve.metrics()).containsEntry("diskSpaceProbeFailures", 1L);
    }

    @Test
    void zeroDisablesTheReserveAndNegativeSettingsAreRejected() {
        var reserve = new RequestLogDiskReserve(0, () -> { throw new AssertionError("must not probe"); }, () -> 0);
        assertThat(reserve.refresh()).isTrue();
        assertThat(reserve.blocked()).isFalse();
        assertThatThrownBy(() -> new RequestLogDiskReserve(-1, () -> 0, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LogTask task(String endpoint) {
        return LogTask.builder().protocol(Protocol.HTTP).method("GET").endpoint(endpoint).build();
    }
}
