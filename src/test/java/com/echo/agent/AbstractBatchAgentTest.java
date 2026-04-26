package com.echo.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AbstractBatchAgent 單元測試 — 生命週期、flush interval、processBatch 例外處理、castTask 型別不符。
 * Validates: Requirements 2.3, 2.4, 2.5
 */
class AbstractBatchAgentTest {

    private TestBatchAgent agent;

    @AfterEach
    void tearDown() {
        if (agent != null && agent.getStatus() == AgentStatus.RUNNING) {
            agent.shutdown();
        }
    }

    // ==================== Test Subclass ====================

    /**
     * 具體測試子類別，記錄所有 processBatch 呼叫以供驗證。
     * 可選擇性地在 processBatch 中拋出例外。
     */
    static class TestBatchAgent extends AbstractBatchAgent<String> {

        private final CopyOnWriteArrayList<List<String>> batchCalls = new CopyOnWriteArrayList<>();
        private volatile boolean throwOnProcess = false;
        private final AtomicInteger processCallCount = new AtomicInteger();
        private volatile CountDownLatch processLatch;

        TestBatchAgent(int queueCapacity, int batchSize, int flushIntervalSeconds) {
            super(queueCapacity, batchSize, flushIntervalSeconds);
        }

        @Override
        public String getName() {
            return "test-agent";
        }

        @Override
        public String getDescription() {
            return "test agent";
        }

        @Override
        protected void processBatch(List<String> batch) {
            processCallCount.incrementAndGet();
            batchCalls.add(new ArrayList<>(batch));
            if (processLatch != null) {
                processLatch.countDown();
            }
            if (throwOnProcess) {
                throw new RuntimeException("Simulated processBatch failure");
            }
        }

        @Override
        protected String castTask(Object task) {
            if (task instanceof String string) {
                return string;
            }
            return null;
        }

        List<List<String>> getBatchCalls() {
            return Collections.unmodifiableList(batchCalls);
        }

        int totalProcessedItems() {
            return batchCalls.stream().mapToInt(List::size).sum();
        }
    }

    // ==================== Lifecycle Tests ====================

    @Test
    void start_shouldTransitionFromStoppedToRunning() {
        agent = new TestBatchAgent(10, 5, 60);

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);

        agent.start();

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.RUNNING);
    }

    @Test
    void shutdown_shouldTransitionFromRunningToStopped() {
        agent = new TestBatchAgent(10, 5, 60);
        agent.start();

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.RUNNING);

        agent.shutdown();

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);
    }

    @Test
    void shutdown_shouldFlushRemainingTasksBeforeTransitionToStopped() {
        agent = new TestBatchAgent(100, 200, 60);
        agent.start();

        for (int i = 0; i < 10; i++) {
            agent.submit("task-" + i);
        }

        agent.shutdown();

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);
        assertThat(agent.getStats().getProcessedCount()).isEqualTo(10);
    }

    @Test
    void fullLifecycle_stoppedToRunningToStopped() {
        agent = new TestBatchAgent(10, 5, 60);

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);

        agent.start();
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.RUNNING);

        agent.shutdown();
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);
    }

    // ==================== Flush Interval Tests (Requirement 2.4) ====================

    @Test
    void flushInterval_shouldTriggerFlushByTime() throws InterruptedException {
        // Use 1-second flush interval, large batch size so only time triggers flush
        agent = new TestBatchAgent(100, 1000, 1);
        agent.processLatch = new CountDownLatch(1);
        agent.start();

        agent.submit("time-triggered-task");

        // Wait for the scheduled flush to fire (1s interval + some tolerance)
        boolean flushed = agent.processLatch.await(3, TimeUnit.SECONDS);

        assertThat(flushed).isTrue();
        assertThat(agent.totalProcessedItems()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void flushInterval_shouldFlushPartialBatchByTime() throws InterruptedException {
        // batch size = 100, but only submit 3 items; flush interval = 1s should pick them up
        agent = new TestBatchAgent(100, 100, 1);
        agent.processLatch = new CountDownLatch(1);
        agent.start();

        agent.submit("item-1");
        agent.submit("item-2");
        agent.submit("item-3");

        boolean flushed = agent.processLatch.await(3, TimeUnit.SECONDS);

        assertThat(flushed).isTrue();
        assertThat(agent.totalProcessedItems()).isEqualTo(3);
    }

    // ==================== processBatch Exception Tests (Requirement 2.5) ====================

    @Test
    void processBatch_exceptionShouldNotStopScheduler() throws InterruptedException {
        agent = new TestBatchAgent(100, 1, 60);
        agent.throwOnProcess = true;
        agent.start();

        // First submit triggers flush → processBatch throws
        agent.submit("fail-task");

        // Small delay to let the flush happen
        Thread.sleep(200);

        // Now disable exception and submit again
        agent.throwOnProcess = false;
        agent.processLatch = new CountDownLatch(1);
        agent.submit("success-task");

        boolean flushed = agent.processLatch.await(3, TimeUnit.SECONDS);

        assertThat(flushed).isTrue();
        // Both calls happened — the scheduler was not killed by the first exception
        assertThat(agent.processCallCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void processBatch_exceptionDuringScheduledFlush_shouldNotStopScheduler() throws InterruptedException {
        // Use 1s flush interval, large batch size so only time triggers flush
        agent = new TestBatchAgent(100, 1000, 1);
        agent.throwOnProcess = true;
        agent.start();

        agent.submit("fail-task-1");

        // Wait for first scheduled flush to fire and fail
        Thread.sleep(1500);

        // Disable exception, submit more, wait for next scheduled flush
        agent.throwOnProcess = false;
        agent.processLatch = new CountDownLatch(1);
        agent.submit("success-task");

        boolean flushed = agent.processLatch.await(3, TimeUnit.SECONDS);

        assertThat(flushed).isTrue();
        assertThat(agent.processCallCount.get()).isGreaterThanOrEqualTo(2);
    }

    // ==================== castTask Type Mismatch Tests ====================

    @Test
    void submit_withIncompatibleType_shouldDropTaskSilently() {
        agent = new TestBatchAgent(10, 5, 60);
        agent.start();

        // Submit an Integer instead of String — castTask returns null
        agent.submit(12345);

        assertThat(agent.getStats().getQueueSize()).isZero();
        assertThat(agent.getBatchCalls()).isEmpty();
        // Not counted as dropped (dropped is for queue-full), just silently ignored
        assertThat(agent.getStats().getDroppedCount()).isZero();
    }

    @Test
    void submit_withNullCastResult_shouldNotAddToQueue() {
        agent = new TestBatchAgent(10, 5, 60);
        agent.start();

        agent.submit(new Object());

        assertThat(agent.getStats().getQueueSize()).isZero();
        assertThat(agent.getBatchCalls()).isEmpty();
    }

    @Test
    void submit_withCorrectType_shouldAddToQueue() {
        agent = new TestBatchAgent(100, 1000, 60);
        agent.start();

        agent.submit("valid-task");

        assertThat(agent.getStats().getQueueSize()).isEqualTo(1);
    }

    @Test
    void submit_mixedTypes_shouldOnlyAcceptCorrectType() {
        agent = new TestBatchAgent(100, 1000, 60);
        agent.start();

        agent.submit("valid-1");
        agent.submit(42);
        agent.submit("valid-2");
        agent.submit(3.14);
        agent.submit("valid-3");

        assertThat(agent.getStats().getQueueSize()).isEqualTo(3);
    }
}
