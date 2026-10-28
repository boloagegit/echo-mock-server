package com.echo.service;

import com.echo.config.AdminLogQueryProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded FIFO admission. A permit covers the entire synchronous response, including serialization. */
@Component
public final class AdminLogQueryAdmission {
    private final boolean enabled;
    private final int concurrency;
    private final int queueCapacity;
    private final long maxWaitNanos;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final ArrayDeque<Condition> waiters = new ArrayDeque<>();
    private int active;
    private long admitted;
    private long rejected;
    private long timedOut;
    private long interrupted;
    private long longestWaitNanos;

    public AdminLogQueryAdmission(AdminLogQueryProperties properties) {
        enabled = properties.isEnabled();
        concurrency = properties.getConcurrency();
        queueCapacity = properties.getQueueCapacity();
        long waitMillis = properties.getMaxWaitMillis();
        if (concurrency < 1 || concurrency > 16 || queueCapacity < 0 || queueCapacity > 128
                || waitMillis < 0 || waitMillis > 5000) {
            throw new IllegalArgumentException("Invalid admin log query admission limits");
        }
        maxWaitNanos = TimeUnit.MILLISECONDS.toNanos(waitMillis);
    }

    public boolean isEnabled() { return enabled; }

    /** Return null when capacity or the bounded wait is exhausted. */
    public Lease acquire() throws InterruptedException {
        long started = System.nanoTime();
        lock.lockInterruptibly();
        try {
            if (active < concurrency && waiters.isEmpty()) return admit(started);
            if (waiters.size() >= queueCapacity || maxWaitNanos == 0) {
                rejected++;
                return null;
            }
            Condition waiter = lock.newCondition();
            waiters.addLast(waiter);
            try {
                long remaining = maxWaitNanos - (System.nanoTime() - started);
                while (true) {
                    if (remaining <= 0) {
                        rejected++;
                        timedOut++;
                        return null;
                    }
                    if (waiters.peekFirst() == waiter && active < concurrency) {
                        waiters.removeFirst();
                        return admit(started);
                    }
                    remaining = Math.min(waiter.awaitNanos(remaining),
                            maxWaitNanos - (System.nanoTime() - started));
                }
            } catch (InterruptedException e) {
                interrupted++;
                throw e;
            } finally {
                waiters.remove(waiter);
                signalNext();
            }
        } finally {
            lock.unlock();
        }
    }

    private Lease admit(long started) {
        active++;
        admitted++;
        longestWaitNanos = Math.max(longestWaitNanos, System.nanoTime() - started);
        return new Lease();
    }

    private void signalNext() {
        if (active < concurrency && !waiters.isEmpty()) waiters.peekFirst().signal();
    }

    public Map<String, Object> snapshot() {
        lock.lock();
        try {
            return Map.of("enabled", enabled, "active", active, "queued", waiters.size(),
                    "concurrency", concurrency, "queueCapacity", queueCapacity, "admitted", admitted,
                    "rejected", rejected, "timedOut", timedOut, "interrupted", interrupted,
                    "longestAdmittedWaitMillis", TimeUnit.NANOSECONDS.toMillis(longestWaitNanos));
        } finally {
            lock.unlock();
        }
    }

    public final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease() { }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            lock.lock();
            try {
                active--;
                signalNext();
            } finally {
                lock.unlock();
            }
        }
    }
}
