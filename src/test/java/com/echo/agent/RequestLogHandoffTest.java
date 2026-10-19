package com.echo.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Request record temporary storage area: no wait for writing, discard the oldest one when the upper limit is exceeded, and keep it for retry when writing fails. */
class RequestLogHandoffTest {

    private final List<String> written = Collections.synchronizedList(new ArrayList<>());
    private RequestLogHandoff handoff;

    @AfterEach
    void stop() {
        if (handoff != null) {
            handoff.stop(1_000);
        }
    }

    private static LogTask task(String endpoint) {
        return LogTask.builder().endpoint(endpoint).build();
    }

    private static LogTask task(String endpoint, int bodyChars) {
        return LogTask.builder().endpoint(endpoint).requestBody("x".repeat(bodyChars)).build();
    }

    private RequestLogHandoff.Sink recording() {
        return batch -> {
            batch.forEach(t -> written.add(t.getEndpoint()));
            return new RequestLogSpool.AppendResult(batch.size(), 0);
        };
    }

    /** The write end is blocked until gate is opened; used to allow records to accumulate in the temporary area. */
    private RequestLogHandoff.Sink blockedUntil(CountDownLatch gate, CountDownLatch entered) {
        return batch -> {
            entered.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            batch.forEach(t -> written.add(t.getEndpoint()));
            return new RequestLogSpool.AppendResult(batch.size(), 0);
        };
    }

    @Test
    @DisplayName("Writes records in order without blocking enqueue")
    void writesInOrderWithoutWaiting() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        handoff = new RequestLogHandoff(100, 1 << 20, blockedUntil(gate, new CountDownLatch(1)), 10, System::nanoTime);
        handoff.start();

        long started = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            handoff.offer(task("/" + i));
        }
        // When the writing end is blocked, the input still returns immediately.
        assertThat(System.nanoTime() - started).isLessThan(TimeUnit.MILLISECONDS.toNanos(200));
        gate.countDown();

        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(written).hasSize(50).first().isEqualTo("/0");
        assertThat(written).last().isEqualTo("/49");
        assertThat(handoff.droppedCount()).isZero();
    }

    @Test
    void largeBodiesAreSplitIntoOrderedBatchesWithoutRejectingAnAdmittedLargeEntry() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        List<List<LogTask>> batches = Collections.synchronizedList(new ArrayList<>());
        handoff = new RequestLogHandoff(1_000, 64L * 1024 * 1024, batch -> {
            if (batches.isEmpty()) {
                entered.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            batches.add(List.copyOf(batch));
            return new RequestLogSpool.AppendResult(batch.size(), 0);
        }, 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/first"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        String body = "x".repeat(2 * 1024 * 1024);
        for (int i = 0; i < 6; i++) {
            handoff.offer(LogTask.builder().endpoint("/" + i).requestBody(body).build());
        }
        handoff.offer(task("/larger-than-batch", 9 * 1024 * 1024));
        handoff.offer(task("/last"));
        gate.countDown();

        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(batches.stream().flatMap(List::stream).map(LogTask::getEndpoint).toList())
                .containsExactly("/first", "/0", "/1", "/2", "/3", "/4", "/5", "/larger-than-batch", "/last");
        assertThat(batches).hasSize(5);
        for (List<LogTask> batch : batches) {
            if (batch.size() > 1) {
                assertThat(batch.stream().mapToLong(RequestLogHandoff::estimateBytes).sum())
                        .isLessThanOrEqualTo(RequestLogHandoff.MAX_BATCH_ESTIMATED_BYTES);
            }
        }
        assertThat(handoff.droppedCount()).isZero();
        assertThat(handoff.bytes()).isZero();
    }

    @Test
    @DisplayName("Exceeding the record limit drops the oldest records and retains the newest")
    void dropsTheOldestOverTheEntryLimit() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        handoff = new RequestLogHandoff(10, 1 << 20, blockedUntil(gate, entered), 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/first"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // While the writer handles /first, enqueue 25 more records; a capacity of 10 including the in-flight record retains only the newest 9 queued records.
        for (int i = 0; i < 25; i++) {
            handoff.offer(task("/" + i));
        }
        assertThat(handoff.size()).isEqualTo(10);
        gate.countDown();

        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(written).containsExactly("/first", "/16", "/17", "/18", "/19", "/20", "/21", "/22",
                "/23", "/24");
        assertThat(handoff.droppedCount()).isEqualTo(16);
        assertThat(handoff.overflowDroppedCount()).isEqualTo(16);
        assertThat(handoff.writeNotConfirmedCount()).isZero();
    }

    @Test
    @DisplayName("Exceeding the memory limit drops the oldest records and rejects individually oversized records")
    void dropsTheOldestOverTheByteLimit() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        long oneEntry = RequestLogHandoff.estimateBytes(task("/0", 10_000));
        handoff = new RequestLogHandoff(1_000, oneEntry * 3, blockedUntil(gate, entered), 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/first"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < 5; i++) {
            handoff.offer(task("/" + i, 10_000));
        }
        handoff.offer(task("/huge", 1_000_000));
        assertThat(handoff.bytes()).isLessThanOrEqualTo(oneEntry * 3);
        gate.countDown();

        assertThat(handoff.awaitIdle(5_000)).isTrue();
        // /first being written also occupies memory: only the latest 2 entries can be left.
        assertThat(written).containsExactly("/first", "/3", "/4");
        assertThat(handoff.droppedCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("A write failure retains buffered records and retries in order without duplicates after recovery")
    void keepsEntriesWhileWritesFailAndRetries() throws Exception {
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        handoff = new RequestLogHandoff(100, 1 << 20, batch -> {
            attempts.incrementAndGet();
            if (failing.get()) {
                throw new IllegalStateException("spool unavailable");
            }
            batch.forEach(t -> written.add(t.getEndpoint()));
            return new RequestLogSpool.AppendResult(batch.size(), 0);
        }, 10, System::nanoTime);
        handoff.start();
        for (int i = 0; i < 5; i++) {
            handoff.offer(task("/" + i));
        }
        while (attempts.get() < 3) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(written).isEmpty();

        failing.set(false);
        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(written).containsExactly("/0", "/1", "/2", "/3", "/4");
        assertThat(handoff.droppedCount()).isZero();
    }

    @Test
    @DisplayName("A processed prefix is not resubmitted; count unstored records as lost and retry the remainder")
    void retriesOnlyWhatWasNotHandled() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        handoff = new RequestLogHandoff(100, 1 << 20, batch -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                entered.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (call == 2) {
                // The first record was processed but not stored, for example because it was oversized; subsequent writes are temporarily unavailable.
                return new RequestLogSpool.AppendResult(1, 1);
            }
            batch.forEach(t -> written.add(t.getEndpoint()));
            return new RequestLogSpool.AppendResult(batch.size(), 0);
        }, 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/0"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        handoff.offer(task("/1"));
        handoff.offer(task("/2"));
        handoff.offer(task("/3"));
        gate.countDown();

        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(written).containsExactly("/0", "/2", "/3");
        assertThat(handoff.droppedCount()).isEqualTo(1);
        assertThat(handoff.writeNotConfirmedCount()).isEqualTo(1);
        assertThat(handoff.overflowDroppedCount()).isZero();
        assertThat(handoff.otherDroppedCount()).isZero();
        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("Shutdown drains the buffer and drops records received after shutdown")
    void stopDrainsAndLaterEntriesAreDropped() {
        handoff = new RequestLogHandoff(1_000, 1 << 20, recording(), 10, System::nanoTime);
        handoff.start();
        for (int i = 0; i < 300; i++) {
            handoff.offer(task("/" + i));
        }

        handoff.stop(5_000);
        handoff.offer(task("/late"));

        assertThat(written).hasSize(300);
        assertThat(handoff.droppedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A shutdown timeout stops the writer without spinning and counts unfinished records as dropped")
    void stopTimingOutMidBatchEndsTheWriterAndCountsTheRest() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        handoff = new RequestLogHandoff(100, 1 << 20, batch -> {
            entered.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // The spool stopped accepting writes during shutdown; none of this batch was written.
            return new RequestLogSpool.AppendResult(0, 0);
        }, 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/0"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 1; i < 10; i++) {
            handoff.offer(task("/" + i));
        }

        Logger logger = (Logger) LoggerFactory.getLogger(RequestLogHandoff.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            long started = System.nanoTime();
            handoff.stop(200);
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(2));
            gate.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (handoff.writerAlive() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
        } finally {
            logger.detachAppender(appender);
        }
        // Drop the 9 queued records on shutdown; the failed in-flight record also counts as dropped and must not be requeued.
        assertThat(handoff.writerAlive()).isFalse();
        assertThat(handoff.droppedCount()).isEqualTo(10);
        assertThat(handoff.bytes()).isZero();
        // Logged drop counts must also total 10; report the in-flight record after shutdown.
        int reported = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Request logging dropped "))
                .mapToInt(m -> Integer.parseInt(m.replaceAll("^Request logging dropped (\\d+) entries.*$", "$1")))
                .sum();
        assertThat(reported).isEqualTo(10);
        handoff = null;
    }

    @Test
    @DisplayName("An Error during writing does not stop the writer or subsequent writes")
    void anErrorWhileWritingDoesNotStopTheWriter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        handoff = new RequestLogHandoff(100, 1 << 20, batch -> {
            if (calls.incrementAndGet() == 1) {
                throw new OutOfMemoryError("simulated");
            }
            batch.forEach(t -> written.add(t.getEndpoint()));
            return new RequestLogSpool.AppendResult(batch.size(), 0);
        }, 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/0"));
        handoff.offer(task("/1"));

        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(handoff.writerAlive()).isTrue();
        assertThat(written).containsExactly("/0", "/1");
    }

    @Test
    @DisplayName("The in-flight batch counts toward the memory limit")
    void theBatchBeingWrittenCountsTowardsTheMemoryLimit() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        long oneEntry = RequestLogHandoff.estimateBytes(task("/0", 10_000));
        long limit = oneEntry * 3 + 2_000;
        handoff = new RequestLogHandoff(1_000, limit, blockedUntil(gate, entered), 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/first", 10_000));
        handoff.offer(task("/second", 10_000));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // The 1-2 in-flight records still occupy memory, leaving room for only 1-2 more records.
        for (int i = 0; i < 5; i++) {
            handoff.offer(task("/" + i, 10_000));
        }
        assertThat(handoff.bytes()).isLessThanOrEqualTo(limit);
        assertThat(handoff.size()).isLessThanOrEqualTo(3);
        gate.countDown();
        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(written).contains("/first", "/4").hasSizeLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("The in-flight batch counts toward the record limit")
    void theBatchBeingWrittenCountsTowardsTheEntryLimit() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        handoff = new RequestLogHandoff(3, 1 << 20, blockedUntil(gate, entered), 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/first"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < 5; i++) {
            handoff.offer(task("/" + i));
        }

        // The in-flight /first record occupies one slot, leaving room for at most 2 queued records.
        assertThat(handoff.size()).isEqualTo(3);
        gate.countDown();
        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(written).containsExactly("/first", "/3", "/4");
        assertThat(handoff.droppedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Warns when record creation fails before buffering")
    void droppingBeforeTheBufferAlsoWarns() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLogHandoff.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handoff = new RequestLogHandoff(10, 1 << 20, recording(), 10, System::nanoTime);
            handoff.start();
            handoff.countDropped();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Request logging dropped 1 entries (the oldest first when writing falls behind; "
                        + "buffer limit 10 entries / 1 MB). Mock responses were not delayed");
    }

    @Test
    @DisplayName("Size estimates include every string field, including skipped scenario rules")
    void sizeEstimateCountsEveryStringField() {
        long empty = RequestLogHandoff.estimateBytes(LogTask.builder().build());
        String big = "x".repeat(100_000);

        for (LogTask task : List.of(
                LogTask.builder().scenarioSkipped(big).build(),
                LogTask.builder().matchChain(big).build(),
                LogTask.builder().analysisBody(big).build(),
                LogTask.builder().diagnosticId(big).build(),
                LogTask.builder().targetHost(big).build())) {
            assertThat(RequestLogHandoff.estimateBytes(task) - empty).isGreaterThanOrEqualTo(200_000);
        }
    }

    @Test
    @DisplayName("Drop warnings are emitted even while the writer is stuck")
    void dropWarningAppearsWhileTheWriterIsStuck() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLogHandoff.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            handoff = new RequestLogHandoff(3, 1 << 20, blockedUntil(gate, entered), 10, System::nanoTime);
            handoff.start();
            handoff.offer(task("/first"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 6; i++) {
                handoff.offer(task("/" + i));
            }

            assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).isNotEmpty();
        } finally {
            gate.countDown();
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("Dropping records emits a warning with the dropped count and limit")
    void warnsAboutDroppedEntries() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLogHandoff.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            handoff = new RequestLogHandoff(5, 1 << 20, blockedUntil(gate, entered), 10, System::nanoTime);
            handoff.start();
            handoff.offer(task("/first"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 12; i++) {
                handoff.offer(task("/" + i));
            }
            gate.countDown();
            assertThat(handoff.awaitIdle(5_000)).isTrue();
            handoff.stop(1_000);
        } finally {
            logger.detachAppender(appender);
        }

        // Warn for the first discard, and then at most every 30 seconds; the number of warnings adds up to the total number of discards
        List<String> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).isNotEmpty().allMatch(w -> w.startsWith("Request logging dropped ")
                && w.endsWith(" entries (the oldest first when writing falls behind; buffer limit 5 entries / 1 MB). "
                        + "Mock responses were not delayed"));
        int reported = warnings.stream()
                .mapToInt(w -> Integer.parseInt(w.replaceAll("^Request logging dropped (\\d+) entries.*$", "$1")))
                .sum();
        // A capacity of 5 includes the in-flight record, so only the newest 4 of the 12 queued records fit.
        assertThat(reported).isEqualTo(8);
    }
}
