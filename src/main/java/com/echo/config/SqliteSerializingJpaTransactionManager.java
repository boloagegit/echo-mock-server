package com.echo.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes SQLite write transactions while allowing read-only transactions to
 * continue using WAL's concurrent-reader support.
 */
public class SqliteSerializingJpaTransactionManager extends JpaTransactionManager {

    private static final long serialVersionUID = 1L;

    private final ReentrantLock writerLock = new ReentrantLock(true);
    private final AtomicInteger queuedWriters = new AtomicInteger();
    private final AtomicInteger activeWriters = new AtomicInteger();
    private final transient ThreadLocal<Deque<Boolean>> transactionLocks =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final transient Timer writerWaitTimer;

    public SqliteSerializingJpaTransactionManager(EntityManagerFactory entityManagerFactory,
                                                   MeterRegistry meterRegistry) {
        super(entityManagerFactory);
        if (meterRegistry == null) {
            writerWaitTimer = null;
            return;
        }

        Gauge.builder("echo.sqlite.writer.queue", queuedWriters, AtomicInteger::get)
                .description("Number of SQLite write transactions waiting for the in-process writer")
                .register(meterRegistry);
        Gauge.builder("echo.sqlite.writer.active", activeWriters, AtomicInteger::get)
                .description("Number of active SQLite write transactions")
                .register(meterRegistry);
        writerWaitTimer = Timer.builder("echo.sqlite.writer.wait")
                .description("Time spent waiting for the in-process SQLite writer")
                .register(meterRegistry);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        boolean writeTransaction = !definition.isReadOnly();
        if (!writeTransaction) {
            super.doBegin(transaction, definition);
            transactionLocks.get().push(Boolean.FALSE);
            return;
        }

        long waitStarted = System.nanoTime();
        queuedWriters.incrementAndGet();
        try {
            writerLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CannotCreateTransactionException(
                    "Interrupted while waiting for the SQLite writer", e);
        } finally {
            queuedWriters.decrementAndGet();
            if (writerWaitTimer != null) {
                writerWaitTimer.record(Duration.ofNanos(System.nanoTime() - waitStarted));
            }
        }

        activeWriters.incrementAndGet();
        try {
            super.doBegin(transaction, definition);
            transactionLocks.get().push(Boolean.TRUE);
        } catch (RuntimeException | Error e) {
            releaseWriter();
            throw e;
        }
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        Deque<Boolean> locks = transactionLocks.get();
        boolean releaseWriter = !locks.isEmpty() && locks.pop();
        try {
            super.doCleanupAfterCompletion(transaction);
        } finally {
            if (releaseWriter) {
                releaseWriter();
            }
            if (locks.isEmpty()) {
                transactionLocks.remove();
            }
        }
    }

    int getQueuedWriterCount() {
        return queuedWriters.get();
    }

    int getActiveWriterCount() {
        return activeWriters.get();
    }

    private void releaseWriter() {
        activeWriters.decrementAndGet();
        writerLock.unlock();
    }
}
