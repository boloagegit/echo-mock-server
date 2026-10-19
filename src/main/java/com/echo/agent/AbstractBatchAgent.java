package com.echo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * Generic batch processing base class, encapsulating common logic such as queue management, scheduler, batch strategy, etc.
 *
 * @param <T> Task type
 */
@Slf4j
public abstract class AbstractBatchAgent<T> implements EchoAgent {

    private static final long DROP_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final LinkedBlockingQueue<T> queue;
    private final int batchSize;
    private final int flushIntervalSeconds;

    private volatile AgentStatus status = AgentStatus.STOPPED;
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong retentionSkippedCount = new AtomicLong();
    private final AtomicLong otherDroppedCount = new AtomicLong();
    private final AtomicLong droppedSinceLastWarning = new AtomicLong();
    private final AtomicLong lastDropWarningNanos = new AtomicLong();

    private ExecutorService consumerExecutor;

    protected AbstractBatchAgent(int queueCapacity, int batchSize, int flushIntervalSeconds) {
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
        this.flushIntervalSeconds = flushIntervalSeconds;
    }

    @Override
    public AgentStatus getStatus() {
        return status;
    }

    @Override
    public AgentStats getStats() {
        return AgentStats.builder()
                .queueSize(queue.size())
                .processedCount(processedCount.get())
                .droppedCount(droppedCount.get())
                .retentionSkippedCount(retentionSkippedCount.get())
                .otherDroppedCount(otherDroppedCount.get())
                .build();
    }

    @Override
    public void submit(Object task) {
        if (status != AgentStatus.RUNNING) {
            recordDropped("agent status is " + status);
            return;
        }
        T typed = castTask(task);
        if (typed == null) {
            log.warn("Agent {} received incompatible task type, dropping", getName());
            return;
        }
        offer(typed);
    }

    /**
     * Lazily creates and submits a task only when the bounded queue has room.
     * This keeps expensive log snapshots and body copies off overloaded request paths.
     */
    public boolean submitLazy(Supplier<? extends T> taskSupplier) {
        Objects.requireNonNull(taskSupplier, "taskSupplier");
        if (status != AgentStatus.RUNNING) {
            recordDropped("agent status is " + status);
            return false;
        }
        if (queue.remainingCapacity() == 0) {
            recordDropped("queue is full");
            return false;
        }

        final T task;
        try {
            task = taskSupplier.get();
        } catch (RuntimeException e) {
            recordDropped("task creation failed");
            log.debug("Agent {} task creation failed", getName(), e);
            return false;
        }
        return task != null && offer(task);
    }

    @Override
    public synchronized void start() {
        if (status == AgentStatus.RUNNING) {
            log.warn("Agent {} is already running, ignoring start()", getName());
            return;
        }
        status = AgentStatus.STARTING;
        consumerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, getName() + "-consumer");
            t.setDaemon(true);
            return t;
        });
        status = AgentStatus.RUNNING;
        consumerExecutor.execute(this::consumeLoop);
        log.info("Agent {} started (batchSize={}, flushInterval={}s)", getName(), batchSize, flushIntervalSeconds);
    }

    @Override
    public synchronized void shutdown() {
        if (status == AgentStatus.STOPPED) {
            return;
        }
        status = AgentStatus.STOPPING;
        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
            try {
                if (!consumerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    discardQueuedTasks("shutdown timed out");
                }
            } catch (InterruptedException e) {
                discardQueuedTasks("shutdown interrupted");
                Thread.currentThread().interrupt();
            }
        }
        status = AgentStatus.STOPPED;
        log.info("Agent {} stopped. processed={}, dropped={}", getName(), processedCount.get(), droppedCount.get());
    }

    /**
     * Single-consumer loop. The submitting thread never calls processBatch().
     * A partial batch waits up to flushIntervalSeconds; a full batch is processed immediately.
     */
    private void consumeLoop() {
        List<T> batch = new ArrayList<>(batchSize);
        try {
            while (status == AgentStatus.RUNNING) {
                T first = queue.poll(flushIntervalSeconds, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(flushIntervalSeconds);
                while (batch.size() < batchSize && status == AgentStatus.RUNNING) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    T next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }

                processBatchSafely(batch);
                // Reuse the same list so that collecting another batch requires no allocation under memory pressure.
                batch.clear();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            queue.drainTo(batch);
            processBatchSafely(batch);
        }
    }

    private boolean offer(T task) {
        if (status != AgentStatus.RUNNING) {
            recordDropped("agent status is " + status);
            return false;
        }
        if (!queue.offer(task)) {
            recordDropped("queue is full");
            return false;
        }
        return true;
    }

    private void processBatchSafely(List<T> batch) {
        if (batch.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            processBatch(batch);
            processedCount.addAndGet(batch.size());
        } catch (Exception | Error e) {
            // Even Error (such as OutOfMemoryError) must only abandon this batch, never terminate the sole processing thread.
            try {
                log.error("Agent {} processBatch failed: {}", getName(), e.getMessage(), e);
            } catch (Throwable ignored) {
                // Continue even if recording fails
            }
        } finally {
            afterBatchProcessed(batch.size(), System.nanoTime() - startedAt);
        }
    }

    /**
     * Allows a concrete best-effort agent to yield after a batch. The default is
     * intentionally a no-op so agents without a throughput budget are unaffected.
     */
    protected void afterBatchProcessed(int itemCount, long processingNanos) {
        // Default: no throttling.
    }

    /**
     * Returns true once at least half of the bounded queue is occupied.
     * Subclasses can use this signal to shed optional work while preserving the task itself.
     */
    protected final boolean isQueueUnderPressure() {
        int capacity = queue.size() + queue.remainingCapacity();
        return queue.size() >= Math.max(batchSize, capacity / 2);
    }

    protected final boolean isQueueEmpty() {
        return queue.isEmpty();
    }

    /** Allows a durable subclass consumer to report successfully processed items. */
    protected final void recordProcessed(int itemCount) {
        processedCount.addAndGet(itemCount);
    }

    /** Allows a durable subclass consumer to report items it had to discard. */
    protected final void recordRejected(String reason) {
        recordDropped(reason);
    }

    /** Counts items a durable consumer skipped on purpose (already logged by the caller). */
    protected final void recordSkipped(long itemCount) {
        retentionSkippedCount.addAndGet(itemCount);
        droppedCount.addAndGet(itemCount);
    }

    private void recordDropped(String reason) {
        otherDroppedCount.incrementAndGet();
        long total = droppedCount.incrementAndGet();
        droppedSinceLastWarning.incrementAndGet();
        long now = System.nanoTime();
        long last = lastDropWarningNanos.get();
        if ((last == 0 || now - last >= DROP_WARNING_INTERVAL_NANOS)
                && lastDropWarningNanos.compareAndSet(last, now)) {
            long recent = droppedSinceLastWarning.getAndSet(0);
            log.warn("Agent {} dropping tasks: reason={}, droppedSinceLastWarning={}, totalDropped={}",
                    getName(), reason, recent, total);
        }
    }

    private void discardQueuedTasks(String reason) {
        int discarded = queue.size();
        if (discarded == 0) {
            return;
        }
        queue.clear();
        otherDroppedCount.addAndGet(discarded);
        droppedCount.addAndGet(discarded);
        log.warn("Agent {} discarded {} queued tasks because {}", getName(), discarded, reason);
    }

    /**
     * Subclasses implement batch processing.
     *
     * @param batch The task list of this batch; it will be cleared and reused after return. Please copy it if you need to keep it.
     */
    protected abstract void processBatch(List<T> batch);

    /**
     * Convert tasks of type Object to generic T.
     * null should be returned when the type does not match.
     *
     * @param task Original task object
     * @return The converted task, or null if the type is incompatible
     */
    protected abstract T castTask(Object task);
}
