package com.echo.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit bounds for the native adapter, including callbacks waiting to encode a response. */
public final class NativeHttpResources implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NativeHttpResources.class);
    final ThreadPoolExecutor workers;
    final int maxBodyBytes;
    private final int maxRequests;
    private final long maxBufferedBytes;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong buffered = new AtomicLong();

    NativeHttpResources(int threads, int maxRequests, int maxBodyBytes, long maxBufferedBytes) {
        this(threads, maxRequests, maxBodyBytes, maxBufferedBytes, Duration.ofSeconds(60));
    }

    NativeHttpResources(int threads, int maxRequests, int maxBodyBytes, long maxBufferedBytes, Duration idleTimeout) {
        if (threads < 1 || maxRequests < 1 || maxBodyBytes < 1 || maxBodyBytes == Integer.MAX_VALUE
                || maxBufferedBytes < 1) throw new IllegalArgumentException("Native HTTP limits must be positive and finite");
        this.maxRequests = maxRequests;
        this.maxBodyBytes = maxBodyBytes;
        this.maxBufferedBytes = maxBufferedBytes;
        if (idleTimeout.isNegative()) throw new IllegalArgumentException("Native HTTP worker idle timeout must not be negative");
        workers = new ThreadPoolExecutor(threads, threads, idleTimeout.toNanos(), TimeUnit.NANOSECONDS,
                new ArrayBlockingQueue<>(maxRequests), runnable -> {
                    Thread thread = new Thread(runnable, "native-http-worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(!idleTimeout.isZero());
    }

    Lease acquire() {
        int count;
        do {
            count = active.get();
            if (count >= maxRequests || workers.isShutdown()) return null;
        } while (!active.compareAndSet(count, count + 1));
        return new Lease();
    }

    int active() { return active.get(); }
    long bufferedBytes() { return buffered.get(); }

    /** O(1) counters only; never enumerates requests or their bodies. */
    public Map<String, Object> resourceMetricsSnapshot() {
        return Map.of("nativeActive", active.get(), "nativeBufferedBytes", buffered.get(),
                "nativeQueued", workers.getQueue().size(), "nativeCapacity", maxRequests,
                "nativeWorkerActive", workers.getActiveCount(), "nativeWorkerCapacity", workers.getCorePoolSize(),
                "nativeWorkerThreads", workers.getPoolSize(),
                "nativeBufferLimitBytes", maxBufferedBytes);
    }

    @Override
    public void close() { workers.shutdownNow(); }

    final class Lease implements AutoCloseable {
        private boolean closed;
        private long reserved;
        private long responseBodyBytes;
        private long encodedBodyBytes;
        private final List<Runnable> cleanup = new ArrayList<>(3);

        synchronized boolean closed() { return closed; }

        synchronized boolean reserve(long bytes) {
            if (closed || bytes < 0 || bytes > maxBufferedBytes) return false;
            long used;
            do {
                used = buffered.get();
                if (bytes > maxBufferedBytes - used) return false;
            } while (!buffered.compareAndSet(used, used + bytes));
            reserved += bytes;
            return true;
        }

        synchronized boolean reserveResponseBody(long bytes) {
            if (!replaceReservation(responseBodyBytes, bytes)) return false;
            responseBodyBytes = bytes;
            return true;
        }

        synchronized boolean reserveEncodedBody(long bytes) {
            if (!replaceReservation(encodedBodyBytes, bytes)) return false;
            encodedBodyBytes = bytes;
            return true;
        }

        private boolean replaceReservation(long previous, long bytes) {
            if (closed || bytes < 0) return false;
            long additional = bytes - previous;
            if (additional > 0 && !reserve(additional)) return false;
            if (additional < 0) {
                buffered.addAndGet(additional);
                reserved += additional;
            }
            return true;
        }

        void onClose(Runnable action) {
            synchronized (this) {
                if (!closed) {
                    cleanup.add(action);
                    return;
                }
            }
            runCleanup(action);
        }

        @Override
        public void close() {
            List<Runnable> actions;
            synchronized (this) {
                if (closed) return;
                closed = true;
                buffered.addAndGet(-reserved);
                active.decrementAndGet();
                actions = List.copyOf(cleanup);
                cleanup.clear();
            }
            // Internal callbacks cancel tasks/futures and unlink the connection's close watch.
            for (Runnable action : actions) runCleanup(action);
        }
    }

    private static void runCleanup(Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            // One observer/diagnostic hook must not prevent cancellation or connection unlinking.
            LOG.warn("HTTP request cleanup callback failed", failure);
        }
    }
}
