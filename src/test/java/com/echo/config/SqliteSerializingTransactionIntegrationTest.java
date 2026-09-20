package com.echo.config;

import com.echo.entity.Scenario;
import com.echo.repository.ScenarioRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@DataJpaTest
@Import({SqliteTransactionConfiguration.class,
        SqliteSerializingTransactionIntegrationTest.MetricsConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SqliteSerializingTransactionIntegrationTest {

    private static final Path SQLITE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "echo-serialized-writer-" + UUID.randomUUID() + ".sqlite");

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + SQLITE_PATH
                + "?journal_mode=WAL&busy_timeout=50&synchronous=NORMAL&foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ScenarioRepository scenarios;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void clearScenarios() {
        scenarios.deleteAll();
    }

    @Test
    void secondWriterWaitsBeyondBusyTimeoutAndThenCommits() throws Exception {
        assertThat(jdbcTemplate.queryForObject("PRAGMA busy_timeout", Integer.class))
                .isEqualTo(50);

        TransactionTemplate writes = new TransactionTemplate(transactionManager);
        CountDownLatch firstWriterFlushed = new CountDownLatch(1);
        CountDownLatch releaseFirstWriter = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> writes.executeWithoutResult(status -> {
                scenarios.saveAndFlush(scenario("first-writer"));
                firstWriterFlushed.countDown();
                await(releaseFirstWriter);
            }));

            assertThat(firstWriterFlushed.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> writes.executeWithoutResult(status ->
                    scenarios.saveAndFlush(scenario("second-writer"))));

            Thread.sleep(200);
            assertThat(second.isDone()).isFalse();
            SqliteSerializingJpaTransactionManager sqliteManager =
                    (SqliteSerializingJpaTransactionManager) transactionManager;
            assertThat(sqliteManager.getActiveWriterCount()).isEqualTo(1);
            assertThat(sqliteManager.getQueuedWriterCount()).isEqualTo(1);
            releaseFirstWriter.countDown();

            assertThatNoException().isThrownBy(() -> {
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            });
        } finally {
            releaseFirstWriter.countDown();
            shutdown(executor);
        }

        assertThat(scenarios.findByScenarioName("first-writer")).isPresent();
        assertThat(scenarios.findByScenarioName("second-writer")).isPresent();
    }

    @Test
    void readOnlyTransactionContinuesWhileWriterIsOpen() throws Exception {
        scenarios.saveAndFlush(scenario("committed"));

        TransactionTemplate writes = new TransactionTemplate(transactionManager);
        TransactionTemplate reads = new TransactionTemplate(transactionManager);
        reads.setReadOnly(true);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CountDownLatch writerFlushed = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> writes.executeWithoutResult(status -> {
                scenarios.saveAndFlush(scenario("uncommitted"));
                writerFlushed.countDown();
                await(releaseWriter);
            }));

            assertThat(writerFlushed.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Long> reader = executor.submit(() -> reads.execute(status -> scenarios.count()));

            assertThat(reader.get(2, TimeUnit.SECONDS)).isEqualTo(1L);
            releaseWriter.countDown();
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            shutdown(executor);
        }
    }

    @Test
    void rollbackReleasesWriterForNextTransaction() {
        TransactionTemplate writes = new TransactionTemplate(transactionManager);

        try {
            writes.executeWithoutResult(status -> {
                scenarios.saveAndFlush(scenario("rolled-back"));
                throw new IllegalStateException("force rollback");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("force rollback");
        }

        assertThatNoException().isThrownBy(() -> writes.executeWithoutResult(status ->
                scenarios.saveAndFlush(scenario("after-rollback"))));
        assertThat(scenarios.findByScenarioName("rolled-back")).isEmpty();
        assertThat(scenarios.findByScenarioName("after-rollback")).isPresent();
    }

    @Test
    void interruptedQueuedWriterLeavesQueueUsable() throws Exception {
        TransactionTemplate writes = new TransactionTemplate(transactionManager);
        SqliteSerializingJpaTransactionManager sqliteManager =
                (SqliteSerializingJpaTransactionManager) transactionManager;
        CountDownLatch firstWriterFlushed = new CountDownLatch(1);
        CountDownLatch releaseFirstWriter = new CountDownLatch(1);
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> writes.executeWithoutResult(status -> {
                scenarios.saveAndFlush(scenario("interrupt-holder"));
                firstWriterFlushed.countDown();
                await(releaseFirstWriter);
            }));
            assertThat(firstWriterFlushed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> interrupted = executor.submit(() -> {
                waitingThread.set(Thread.currentThread());
                try {
                    writes.executeWithoutResult(status ->
                            scenarios.saveAndFlush(scenario("should-not-commit")));
                    return null;
                } catch (Throwable failure) {
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                    return failure;
                }
            });

            waitUntil(() -> sqliteManager.getQueuedWriterCount() == 1);
            waitingThread.get().interrupt();
            assertThat(interrupted.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(CannotCreateTransactionException.class)
                    .hasMessageContaining("Interrupted while waiting for the SQLite writer");
            assertThat(interruptPreserved).isTrue();

            releaseFirstWriter.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstWriter.countDown();
            shutdown(executor);
        }

        assertThatNoException().isThrownBy(() -> writes.executeWithoutResult(status ->
                scenarios.saveAndFlush(scenario("after-interrupt"))));
        assertThat(scenarios.findByScenarioName("should-not-commit")).isEmpty();
        assertThat(scenarios.findByScenarioName("after-interrupt")).isPresent();
        assertThat(sqliteManager.getActiveWriterCount()).isZero();
        assertThat(sqliteManager.getQueuedWriterCount()).isZero();
    }

    @Test
    void transactionManagerPublishesWriterState() {
        assertThat(transactionManager)
                .isInstanceOf(SqliteSerializingJpaTransactionManager.class);
        SqliteSerializingJpaTransactionManager sqliteManager =
                (SqliteSerializingJpaTransactionManager) transactionManager;

        assertThat(sqliteManager.getActiveWriterCount()).isZero();
        assertThat(sqliteManager.getQueuedWriterCount()).isZero();
        assertThat(meterRegistry.find("echo.sqlite.writer.active").gauge()).isNotNull();
        assertThat(meterRegistry.find("echo.sqlite.writer.queue").gauge()).isNotNull();
        assertThat(meterRegistry.find("echo.sqlite.writer.wait").timer()).isNotNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MetricsConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static Scenario scenario(String name) {
        return Scenario.builder()
                .scenarioName(name)
                .currentState("Started")
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", e);
        }
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out stopping test executor");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while stopping test executor", e);
        }
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for test condition");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for test condition", e);
            }
        }
    }

    @AfterAll
    static void cleanupSqliteFiles() throws IOException {
        Files.deleteIfExists(Path.of(SQLITE_PATH + "-wal"));
        Files.deleteIfExists(Path.of(SQLITE_PATH + "-shm"));
        Files.deleteIfExists(SQLITE_PATH);
    }
}
