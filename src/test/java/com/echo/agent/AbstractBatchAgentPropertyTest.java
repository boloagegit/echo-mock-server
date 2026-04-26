package com.echo.agent;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AbstractBatchAgent 屬性測試（Property 1–5）。
 * <p>
 * Feature: agent-framework-log-agent
 * <p>
 * 使用 TestBatchAgent（extends AbstractBatchAgent&lt;String&gt;）作為測試子類別，
 * 驗證佇列管理、批次觸發、丟棄策略與關閉行為的正確性。
 */
class AbstractBatchAgentPropertyTest {

    // ==================== Test Subclass ====================

    /**
     * 具體測試子類別，記錄所有 processBatch 呼叫以供驗證。
     */
    static class TestBatchAgent extends AbstractBatchAgent<String> {

        private final CopyOnWriteArrayList<List<String>> batchCalls = new CopyOnWriteArrayList<>();

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
     * 非 RUNNING 狀態下 submit 不增加佇列大小。
     * <p>
     * **Validates: Requirements 1.4**
     */
    @Property(tries = 20)
    void submitRejectedWhenAgentNotRunning(
            @ForAll @IntRange(min = 1, max = 50) int capacity,
            @ForAll @IntRange(min = 1, max = 20) int submitCount) {

        // Agent 建立後預設為 STOPPED，不呼叫 start()
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
     * 任意 N 容量、M 次提交（M &gt; N），佇列大小不超過 N。
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
     * 提交 B 筆後 processBatch 被呼叫。
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

            // After submitting exactly batchSize items, processBatch should have been called
            assertThat(agent.getBatchCalls()).isNotEmpty();
            assertThat(agent.totalProcessedItems()).isGreaterThanOrEqualTo(1);
        } finally {
            agent.shutdown();
        }
    }

    // ==================== Property 4 ====================

    /**
     * Feature: agent-framework-log-agent, Property 4: Queue full discards and increments dropped count
     * <p>
     * 佇列滿後額外 K 次提交，droppedCount 增加 K。
     * <p>
     * **Validates: Requirements 2.6**
     */
    @Property(tries = 20)
    void queueFullDiscardsAndIncrementsDroppedCount(
            @ForAll @IntRange(min = 1, max = 50) int capacity,
            @ForAll @IntRange(min = 1, max = 30) int extraSubmissions) {

        // batchSize larger than capacity to prevent flush from draining
        TestBatchAgent agent = new TestBatchAgent(capacity, capacity + extraSubmissions + 1, 60);
        agent.start();

        try {
            // Fill queue to capacity
            for (int i = 0; i < capacity; i++) {
                agent.submit("task-" + i);
            }

            assertThat(agent.getStats().getDroppedCount()).isZero();

            // Submit K more — these should all be dropped
            for (int i = 0; i < extraSubmissions; i++) {
                agent.submit("extra-" + i);
            }

            assertThat(agent.getStats().getDroppedCount()).isEqualTo(extraSubmissions);
        } finally {
            agent.shutdown();
        }
    }

    // ==================== Property 5 ====================

    /**
     * Feature: agent-framework-log-agent, Property 5: Shutdown flushes all remaining tasks
     * <p>
     * shutdown 後所有剩餘任務被 flush，狀態轉為 STOPPED。
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
}
