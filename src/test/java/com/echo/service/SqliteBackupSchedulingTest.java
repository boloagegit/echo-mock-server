package com.echo.service;

import com.echo.config.SqliteStartupRecovery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteBackupSchedulingTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);
    @TempDir
    Path directory;
    private Path backups;
    private DriverManagerDataSource source;

    @BeforeEach
    void setUp() throws Exception {
        backups = Files.createDirectory(directory.resolve("User's Backups"));
        source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("User's Data.sqlite"));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker(value TEXT NOT NULL)");
            statement.execute("INSERT INTO marker VALUES ('committed')");
        }
    }

    @Test
    void scheduledBackupWaitsForHousekeepingAndThenPublishesAHealthyCopy() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var service = service(gatedClock(entered, release));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var cleanup = workers.submit(service::housekeeping);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var scheduled = workers.submit(() -> {
                started.countDown();
                service.scheduledBackup();
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> scheduled.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> service.backup("manual"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already running");
            assertThat(service.listBackups()).isEmpty();

            release.countDown();
            cleanup.get(3, TimeUnit.SECONDS);
            scheduled.get(3, TimeUnit.SECONDS);
            assertThat(service.listBackups()).hasSize(1);
            try (var files = Files.list(backups)) {
                assertThat(files.toList()).allSatisfy(path ->
                        assertThat(SqliteStartupRecovery.isHealthyDatabase(path)).isTrue());
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void waitingScheduledBackupCanBeInterruptedWithoutStrandingTheMaintenanceLock() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>();
        var service = service(gatedClock(entered, release));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var cleanup = workers.submit(service::housekeeping);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var scheduled = workers.submit(() -> {
                thread.set(Thread.currentThread());
                started.countDown();
                service.scheduledBackup();
                return Thread.currentThread().isInterrupted();
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> scheduled.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            thread.get().interrupt();
            assertThat(scheduled.get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.listBackups()).isEmpty();

            release.countDown();
            cleanup.get(3, TimeUnit.SECONDS);
            String filename = service.backup("after-interruption");
            assertThat(SqliteStartupRecovery.isHealthyDatabase(backups.resolve(filename))).isTrue();
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private SqliteBackupService service(Clock clock) {
        var service = new SqliteBackupService(source, clock, dir -> Long.MAX_VALUE);
        ReflectionTestUtils.setField(service, "backupPath", backups.toString());
        ReflectionTestUtils.setField(service, "retentionDays", 7);
        service.validateSettings();
        return service;
    }

    private Clock gatedClock(CountDownLatch entered, CountDownLatch release) {
        var first = new AtomicBoolean(true);
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return CLOCK.getZone();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return CLOCK.withZone(zone);
            }

            @Override
            public Instant instant() {
                if (first.compareAndSet(true, false)) {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test housekeeping release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return CLOCK.instant();
            }
        };
    }
}
