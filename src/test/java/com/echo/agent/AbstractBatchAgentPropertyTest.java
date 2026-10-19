package com.echo.agent;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AbstractBatchAgent attribute test (Property 1–5).
 * <p>
 * Feature: agent-framework-log-agent
 * <p>
 * Use TestBatchAgent (extends AbstractBatchAgent&lt;String&gt;) as the test subclass,
 * Verify the correctness of queue management, batch triggering, discard policy and shutdown behavior.
 */
class AbstractBatchAgentPropertyTest {

    // ==================== Test Subclass ====================

    /**
     * Concrete test subclass recording every processBatch call for assertions.
     */
    static class TestBatchAgent extends AbstractBatchAgent<String> {

        private final CopyOnWriteArrayList<List<String>> batchCalls = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch processStarted;
        private volatile CountDownLatch releaseProcess;

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
            batchCalls.add(new ArrayList<>(batch));
            if (processStarted != null) {
                processStarted.countDown();
            }
            if (releaseProcess != null) {
                try {
                    releaseProcess.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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

    // ==================== Property 1 ====================

    /**
     * Feature: agent-framework-log-agent, Property 1: Submit rejected when agent not RUNNING
     * <p>
     * submit does not increase the queue size in non-RUNNING state.
     * <p>
     * **Validates: Requirements 1.4**
     */
    @Property(tries = 20)
    void submitRejectedWhenAgentNotRunning(
            @ForAll @IntRange(min = 1, max = 50) int capacity,
            @ForAll @IntRange(min = 1, max = 20) int submitCount) {

        // Agent is defaulted to STOPPED after creation and does not call start()
        TestBatchAgent agent = new TestBatchAgent(capacity, 100, 60);

        assertThat(agent.getStatus()).isNotEqualTo(AgentStatus.RUNNING);

        for (int i = 0; i < submitCount; i++) {
            agent.submit("task-" + i);
        }

        assertThat(agent.getStats().getQueueSize()).isZero();
        assertThat(agent.getBatchCalls()).isEmpty();
    }

    // ==================== Property 2 ====================

    /**
     * Feature: agent-framework-log-agent, Property 2: Queue bounded by capacity
     * <p>
     * For arbitrary capacity N and M submissions (M &gt; N), the queue size never exceeds N.
     * <p>
     * **Validates: Requirements 2.2**
     */
    @Property(tries = 20)
    void queueBoundedByCapacity(
            @ForAll @IntRange(min = 1, max = 100) int capacity,
            @ForAll @IntRange(min = 1, max = 100) int extra) {

        int totalSubmissions = capacity + extra;
        // Use batchSize larger than totalSubmissions to prevent flush from draining the queue
        TestBatchAgent agent = new TestBatchAgent(capacity, totalSubmissions + 1, 60);
        agent.start();

        try {
            for (int i = 0; i < totalSubmissions; i++) {
                agent.submit("task-" + i);
            }

            assertThat(agent.getStats().getQueueSize()).isLessThanOrEqualTo(capacity);
        } finally {
            agent.shutdown();
        }
    }

    // ==================== Property 3 ====================

    /**
     * Feature: agent-framework-log-agent, Property 3: Batch size triggers flush
     * <p>
     * processBatch is called after B records have been submitted.
     * <p>
     * **Validates: Requirements 2.3**
     */
    @Property(tries = 20)
    void batchSizeTriggersFlush(
            @ForAll @IntRange(min = 1, max = 50) int batchSize) {

        // capacity large enough, flush interval very long to avoid time-based flush
        TestBatchAgent agent = new TestBatchAgent(batchSize + 100, batchSize, 60);
        agent.start();

        try {
            for (int i = 0; i < batchSize; i++) {
                agent.submit("task-" + i);
            }

            assertThat(awaitUntil(() -> !agent.getBatchCalls().isEmpty(), 2_000)).isTrue();
            assertThat(agent.totalProcessedItems()).isGreaterThanOrEqualTo(1);
        } finally {
            agent.shutdown();
        }
    }

    // ==================== Property 4 ====================

    /**
     * Feature: agent-framework-log-agent, Property 4: Queue full discards and increments dropped count
     * <p>
     * Submitting K additional tasks to a full queue increases droppedCount by K.
     * <p>
     * **Validates: Requirements 2.6**
     */
    @Property(tries = 20)
    void queueFullDiscardsAndIncrementsDroppedCount(
            @ForAll @IntRange(min = 1, max = 50) int capacity,
            @ForAll @IntRange(min = 1, max = 30) int extraSubmissions) {

        TestBatchAgent agent = new TestBatchAgent(capacity, 1, 60);
        agent.processStarted = new CountDownLatch(1);
        agent.releaseProcess = new CountDownLatch(1);
        agent.start();

        try {
            // Occupy the only consumer, then fill the bounded queue deterministically.
            agent.submit("blocking-task");
            assertThat(agent.processStarted.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < capacity; i++) {
                agent.submit("task-" + i);
            }

            assertThat(agent.getStats().getDroppedCount()).isZero();

            // Submit K more — these should all be dropped
            for (int i = 0; i < extraSubmissions; i++) {
                agent.submit("extra-" + i);
            }

            assertThat(agent.getStats().getDroppedCount()).isEqualTo(extraSubmissions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            agent.releaseProcess.countDown();
            agent.shutdown();
        }
    }

    // ==================== Property 5 ====================

    /**
     * Feature: agent-framework-log-agent, Property 5: Shutdown flushes all remaining tasks
     * <p>
     * All remaining tasks after shutdown are flush, and the status is changed to STOPPED.
     * <p>
     * **Validates: Requirements 2.7**
     */
    @Property(tries = 20)
    void shutdownFlushesAllRemainingTasks(
            @ForAll @IntRange(min = 1, max = 50) int capacity,
            @ForAll @IntRange(min = 1, max = 50) int itemCount) {

        int actualItems = Math.min(itemCount, capacity);
        // batchSize very large to prevent flush during submit, so items stay in queue
        TestBatchAgent agent = new TestBatchAgent(capacity, capacity + 1, 60);
        agent.start();

        for (int i = 0; i < actualItems; i++) {
            agent.submit("task-" + i);
        }

        // Shutdown should flush all remaining items
        agent.shutdown();

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.STOPPED);

        // All submitted items should have been processed (via flush during submit or final flush)
        assertThat(agent.getStats().getProcessedCount()).isEqualTo(actualItems);
    }

    private static boolean awaitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
