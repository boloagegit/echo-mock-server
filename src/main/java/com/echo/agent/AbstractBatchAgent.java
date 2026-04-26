package com.echo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;

/**
 * 泛型批次處理基底類別，封裝佇列管理、排程器、批次策略等共用邏輯。
 *
 * @param <T> 任務型別
 */
@Slf4j
public abstract class AbstractBatchAgent<T> implements EchoAgent {

    private final LinkedBlockingQueue<T> queue;
    private final int batchSize;
    private final int flushIntervalSeconds;
    private final ReentrantLock flushLock = new ReentrantLock();

    private volatile AgentStatus status = AgentStatus.STOPPED;
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();

    private ScheduledExecutorService scheduler;

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
                .build();
    }

    @Override
    public void submit(Object task) {
        if (status != AgentStatus.RUNNING) {
            log.warn("Agent {} is not running (status={}), rejecting task", getName(), status);
            return;
        }
        T typed = castTask(task);
        if (typed == null) {
            log.warn("Agent {} received incompatible task type, dropping", getName());
            return;
        }
        if (!queue.offer(typed)) {
            droppedCount.incrementAndGet();
            log.warn("Agent {} queue full, dropping task. dropped={}", getName(), droppedCount.get());
            return;
        }
        if (queue.size() >= batchSize) {
            triggerFlush();
        }
    }

    @Override
    public void start() {
        if (status == AgentStatus.RUNNING) {
            log.warn("Agent {} is already running, ignoring start()", getName());
            return;
        }
        status = AgentStatus.STARTING;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, getName() + "-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::triggerFlush,
                flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
        status = AgentStatus.RUNNING;
        log.info("Agent {} started (batchSize={}, flushInterval={}s)", getName(), batchSize, flushIntervalSeconds);
    }

    @Override
    public void shutdown() {
        status = AgentStatus.STOPPING;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // Final flush: drain all remaining tasks
        finalFlush();
        status = AgentStatus.STOPPED;
        log.info("Agent {} stopped. processed={}, dropped={}", getName(), processedCount.get(), droppedCount.get());
    }

    /**
     * 觸發一次 flush，使用 lock 確保同一時間只有一個 flush 在執行。
     */
    private void triggerFlush() {
        if (!flushLock.tryLock()) {
            return;
        }
        try {
            List<T> batch = new ArrayList<>(batchSize);
            queue.drainTo(batch, batchSize);
            if (!batch.isEmpty()) {
                try {
                    processBatch(batch);
                    processedCount.addAndGet(batch.size());
                } catch (Exception e) {
                    log.error("Agent {} processBatch failed: {}", getName(), e.getMessage(), e);
                }
            }
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * 最終 flush：排空佇列中所有剩餘任務。
     */
    private void finalFlush() {
        flushLock.lock();
        try {
            List<T> remaining = new ArrayList<>();
            queue.drainTo(remaining);
            if (!remaining.isEmpty()) {
                try {
                    processBatch(remaining);
                    processedCount.addAndGet(remaining.size());
                } catch (Exception e) {
                    log.error("Agent {} final flush failed: {}", getName(), e.getMessage(), e);
                }
            }
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * 子類別實作批次處理邏輯。
     *
     * @param batch 本次批次的任務列表
     */
    protected abstract void processBatch(List<T> batch);

    /**
     * 將 Object 型別的任務轉換為泛型 T。
     * 型別不符時應回傳 null。
     *
     * @param task 原始任務物件
     * @return 轉換後的任務，或 null 表示型別不符
     */
    protected abstract T castTask(Object task);
}
