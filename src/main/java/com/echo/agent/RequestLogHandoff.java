package com.echo.agent;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * In-memory request-log buffer: requests do not wait for disk writes; a background thread drains this buffer in batches.
 *
 * <p>Both the record count and estimated memory are bounded. Drop the oldest records when either limit is exceeded, preserving the newest records as retention does.
 * Report drop counts periodically. Logging must not slow or fail requests. Retain records not yet handed to the spool and retry temporary write failures;
 * count handed-off but unconfirmed writes as dropped, since retrying could duplicate them. An unexpected process exit loses records not yet written.</p>
 */
@Slf4j
final class RequestLogHandoff {

    /** Maximum number of records handed to the writer at once. */
    static final int BATCH_SIZE = 500;
    /** Limit simultaneous task retention during serialization/commit; an admitted larger entry goes alone. */
    static final long MAX_BATCH_ESTIMATED_BYTES = 16L * 1024 * 1024;
    private static final long DROP_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** Write a batch and return the processed-prefix count and the count not stored; resubmit the remaining suffix later. */
    interface Sink {
        RequestLogSpool.AppendResult write(List<LogTask> batch);
    }

    private final int maxEntries;
    private final long maxBytes;
    private final Sink sink;
    private final long retryNanos;
    private final LongSupplier nanoTime;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Condition drained = lock.newCondition();
    /** Pending records, protected by {@link #lock}. */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    /** Estimated size of queued and in-flight records; in-flight writes still occupy memory and count toward the limit until complete. */
    private long bytes;
    private boolean running;
    /** Shutdown is complete: the writer has finished, and unfinished records are dropped rather than requeued. */
    private boolean closed;
    private boolean writing;
    /** In-flight record count: these records still occupy memory until the write completes and count toward the limit and {@link #size()}. */
    private int inFlight;
    private boolean writeFailureLogged;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong overflowDropped = new AtomicLong();
    private final AtomicLong otherDropped = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private long droppedAtLastWarning;
    private long lastWarningNanos;
    private Thread writer;

    RequestLogHandoff(int maxEntries, long maxBytes, Sink sink, long retryMillis, LongSupplier nanoTime) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBytes = Math.max(1, maxBytes);
        this.sink = sink;
        this.retryNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(10, retryMillis));
        this.nanoTime = nanoTime;
    }

    void start() {
        lock.lock();
        try {
            if (running) {
                return;
            }
            running = true;
        } finally {
            lock.unlock();
        }
        writer = new Thread(this::writeLoop, "request-log-handoff");
        writer.setDaemon(true);
        writer.start();
    }

    /** Enqueue without waiting; drop the oldest records when limits are exceeded. Drop new records immediately after shutdown. */
    void offer(LogTask task) {
        long size = estimateBytes(task);
        boolean droppedAny;
        lock.lock();
        try {
            if (!running || size > maxBytes) {
                // Reject a single oversized record without evicting other records to make room for it.
                dropped.incrementAndGet();
                otherDropped.incrementAndGet();
                droppedAny = true;
            } else {
                entries.addLast(new Entry(task, size));
                bytes += size;
                droppedAny = dropOldestOverLimit();
                available.signal();
            }
        } finally {
            lock.unlock();
        }
        if (droppedAny) {
            // Emit warnings even when the writer is stuck, at most once every 30 seconds.
            warnAboutDrops(false);
        }
    }

    /** Count records that never reached the buffer, for example because record creation failed. */
    void countDropped() {
        dropped.incrementAndGet();
        otherDropped.incrementAndGet();
        warnAboutDrops(false);
    }

    /**
     * Stop accepting records, allow at most {@code drainTimeoutMillis} to drain the buffer, then drop the remainder.
     */
    void stop(long drainTimeoutMillis) {
        long remaining = TimeUnit.MILLISECONDS.toNanos(drainTimeoutMillis);
        int left;
        lock.lock();
        try {
            if (!running) {
                return;
            }
            running = false;
            available.signalAll();
            try {
                while ((!entries.isEmpty() || writing) && writer != null && writer.isAlive() && remaining > 0) {
                    remaining = drained.awaitNanos(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            closed = true;
            left = entries.size();
            for (Entry entry : entries) {
                bytes -= entry.bytes();
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
        if (left > 0) {
            dropped.addAndGet(left);
            otherDropped.addAndGet(left);
            log.warn("Request-log hand-off stopped with {} entries not yet written; they are dropped", left);
        }
        if (writer != null) {
            writer.interrupt();
        }
        warnAboutDrops(true);
    }

    long droppedCount() {
        return dropped.get() + lost.get();
    }

    long overflowDroppedCount() { return overflowDropped.get(); }
    long otherDroppedCount() { return otherDropped.get(); }
    long writeNotConfirmedCount() { return lost.get(); }

    long writtenCount() {
        return written.get();
    }

    int size() {
        lock.lock();
        try {
            return entries.size() + inFlight;
        } finally {
            lock.unlock();
        }
    }

    long bytes() {
        lock.lock();
        try {
            return bytes;
        } finally {
            lock.unlock();
        }
    }

    long maxBytes() {
        return maxBytes;
    }

    /** Test hook: whether the writer is still running. */
    boolean writerAlive() {
        return writer != null && writer.isAlive();
    }

    /** Evict the oldest queued records until within limits. The in-flight batch cannot be evicted, but still counts toward memory usage. */
    private boolean dropOldestOverLimit() {
        boolean droppedAny = false;
        while (!entries.isEmpty() && (entries.size() + inFlight > maxEntries || bytes > maxBytes)) {
            bytes -= entries.removeFirst().bytes();
            dropped.incrementAndGet();
            overflowDropped.incrementAndGet();
            droppedAny = true;
        }
        return droppedAny;
    }

    private void writeLoop() {
        while (true) {
            List<Entry> batch = take();
            if (batch == null) {
                return;
            }
            List<LogTask> tasks = new ArrayList<>(batch.size());
            batch.forEach(entry -> tasks.add(entry.task()));
            int handled;
            int lostInBatch;
            try {
                RequestLogSpool.AppendResult result = sink.write(tasks);
                handled = Math.min(result.handled(), batch.size());
                lostInBatch = Math.min(result.lost(), handled);
                writeFailureLogged = false;
            } catch (Throwable e) {
                // Include Error: the writer must survive, or all subsequent records would only be dropped.
                handled = 0;
                lostInBatch = 0;
                if (!writeFailureLogged) {
                    writeFailureLogged = true;
                    log.warn("Request-log hand-off could not write to the spool; will retry: {}", e.toString());
                } else {
                    log.debug("Request-log hand-off write failed again: {}", e.toString());
                }
            }
            written.addAndGet(handled - lostInBatch);
            lost.addAndGet(lostInBatch);
            finish(batch, handled);
            warnAboutDrops(false);
            if (handled < batch.size()) {
                pauseBeforeRetry();
            }
        }
    }

    /** Wait for records or shutdown; return null when stopped and the buffer is empty. */
    private List<Entry> take() {
        lock.lock();
        try {
            while (entries.isEmpty() && !closed) {
                if (!running) {
                    return null;
                }
                available.await();
            }
            if (closed) {
                return null;
            }
            int count = Math.min(BATCH_SIZE, entries.size());
            List<Entry> batch = new ArrayList<>(count);
            long batchBytes = 0;
            for (int i = 0; i < count; i++) {
                Entry next = entries.getFirst();
                if (!batch.isEmpty() && next.bytes() > MAX_BATCH_ESTIMATED_BYTES - batchBytes) {
                    break;
                }
                batch.add(entries.removeFirst());
                batchBytes += next.bytes();
            }
            writing = true;
            inFlight = batch.size();
            return batch;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Requeue unfinished records at the front, still evicting the oldest if limits are exceeded; count them as dropped after shutdown. */
    private void finish(List<Entry> batch, int handled) {
        boolean droppedAfterStop = false;
        lock.lock();
        try {
            for (Entry entry : batch) {
                bytes -= entry.bytes();
            }
            inFlight = 0;
            if (closed) {
                dropped.addAndGet(batch.size() - handled);
                otherDropped.addAndGet(batch.size() - handled);
                droppedAfterStop = batch.size() > handled;
            } else {
                for (int i = batch.size() - 1; i >= handled; i--) {
                    Entry entry = batch.get(i);
                    entries.addFirst(entry);
                    bytes += entry.bytes();
                }
                dropOldestOverLimit();
            }
            writing = false;
            drained.signalAll();
        } finally {
            lock.unlock();
        }
        if (droppedAfterStop) {
            // stop() emitted its final warning before this batch completed; report this batch's remaining drops so shutdown does not undercount them.
            warnAboutDrops(true);
        }
    }

    /** Back off after a write failure, including during shutdown, to avoid a busy loop. */
    private void pauseBeforeRetry() {
        lock.lock();
        try {
            long remaining = retryNanos;
            while (remaining > 0 && !closed) {
                remaining = drained.awaitNanos(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    private synchronized void warnAboutDrops(boolean force) {
        long total = droppedCount();
        long now = nanoTime.getAsLong();
        if (total == droppedAtLastWarning
                || (!force && lastWarningNanos != 0 && now - lastWarningNanos < DROP_WARNING_INTERVAL_NANOS)) {
            return;
        }
        log.warn("Request logging dropped {} entries (the oldest first when writing falls behind; buffer limit "
                        + "{} entries / {} MB). Mock responses were not delayed",
                total - droppedAtLastWarning, maxEntries, maxBytes / (1024 * 1024));
        droppedAtLastWarning = total;
        lastWarningNanos = now;
    }

    /**
     * Estimate a record's heap footprint for the buffer limit: count two bytes per string character,
     * plus fixed object overhead, each header, candidate rules, and matching results.
     */
    static long estimateBytes(LogTask task) {
        long fixed = 512;
        if (task.getCandidates() != null) {
            fixed += (long) task.getCandidates().size() * 128;
        }
        if (task.getMatchOutcomes() != null) {
            fixed += (long) task.getMatchOutcomes().size() * 128;
        }
        // Count every string field: any may be large, such as scenarioSkipped containing skipped-rule conditions.
        long chars = 0;
        for (String value : new String[] {
                task.getRuleId(), task.getMethod(), task.getEndpoint(), task.getClientIp(),
                task.getMatchChain(), task.getTargetHost(), task.getForwardTarget(), task.getProxyError(),
                task.getRequestBody(), task.getResponseBody(), task.getFaultType(), task.getScenarioName(),
                task.getScenarioFromState(), task.getScenarioToState(), task.getScenarioSkipped(),
                task.getAnalysisBody(), task.getQueryString(), task.getDiagnosticId()}) {
            chars += length(value);
        }
        Map<String, String> headers = task.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                chars += length(header.getKey()) + length(header.getValue());
                fixed += 96;
            }
        }
        return fixed + chars * 2;
    }

    private static long length(String value) {
        return value == null ? 0 : value.length();
    }

    private record Entry(LogTask task, long bytes) {
    }

    /** Test hook: wait for the writer to finish processing the current buffer. */
    boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (!entries.isEmpty() || writing) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = drained.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
