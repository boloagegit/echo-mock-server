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

/** 請求記錄暫存區：不等寫入、超過上限丟最舊的、寫入失敗時保留重試。 */
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

    /** 寫入端被擋住，直到 gate 打開；用來讓記錄在暫存區累積。 */
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
    @DisplayName("記錄依序寫入，放入時不等待")
    void writesInOrderWithoutWaiting() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        handoff = new RequestLogHandoff(100, 1 << 20, blockedUntil(gate, new CountDownLatch(1)), 10, System::nanoTime);
        handoff.start();

        long started = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            handoff.offer(task("/" + i));
        }
        // 寫入端被擋住時，放入仍立即返回
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
    @DisplayName("超過筆數上限：丟掉最舊的，保留最新的")
    void dropsTheOldestOverTheEntryLimit() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        handoff = new RequestLogHandoff(10, 1 << 20, blockedUntil(gate, entered), 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/first"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // 寫入端忙著寫 /first 時又來 25 筆：連同寫入中的那筆共 10 筆，只留最新 9 筆
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
    @DisplayName("超過記憶體上限：丟掉最舊的；單筆就超過上限的不保留")
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
        // 寫入中的 /first 也佔記憶體：只留得下最新的 2 筆
        assertThat(written).containsExactly("/first", "/3", "/4");
        assertThat(handoff.droppedCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("寫入失敗：記錄留在暫存區，恢復後依序寫入且不重複")
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
    @DisplayName("只處理完前面幾筆：已處理的不再送（沒存到的計入丟失），其餘重試")
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
                // 第一筆處理了但沒存到（例如太大被拒），之後的暫時寫不進去
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
    @DisplayName("停止時把暫存區寫完；停止後的記錄直接丟掉")
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
    @DisplayName("停止時寫到一半逾時：寫入端結束、不空轉，沒寫完的計入丟棄")
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
            // 停止時暫存檔已不收：這一批都沒寫進去
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
        // 等待中的 9 筆在停止時丟掉；寫入中的那筆沒寫成，也計入丟棄，不再放回重試
        assertThat(handoff.writerAlive()).isFalse();
        assertThat(handoff.droppedCount()).isEqualTo(10);
        assertThat(handoff.bytes()).isZero();
        // 日誌裡報出的丟棄筆數加起來也是 10（寫入中的那筆在停止後補報）
        int reported = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Request logging dropped "))
                .mapToInt(m -> Integer.parseInt(m.replaceAll("^Request logging dropped (\\d+) entries.*$", "$1")))
                .sum();
        assertThat(reported).isEqualTo(10);
        handoff = null;
    }

    @Test
    @DisplayName("寫入時發生 Error：寫入端不停止，之後照常寫入")
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
    @DisplayName("正在寫入的那批也計入記憶體上限")
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

        // 寫入中的 1～2 筆仍佔記憶體：只能再收 1～2 筆
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
    @DisplayName("正在寫入的那批也計入筆數上限")
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

        // 寫入中的 /first 佔一筆：等待中最多 2 筆
        assertThat(handoff.size()).isEqualTo(3);
        gate.countDown();
        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(written).containsExactly("/first", "/3", "/4");
        assertThat(handoff.droppedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("建立記錄失敗而沒放進暫存區：也會警告")
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
    @DisplayName("大小估計包含每個字串欄位（例如被略過的情境規則）")
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
    @DisplayName("寫入端卡住時，丟棄的警告仍會出現")
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
    @DisplayName("丟掉記錄時記錄一次警告，寫出丟了幾筆與上限")
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

        // 第一次丟棄就警告，之後最多每 30 秒一次；各次警告的筆數加起來就是丟掉的總數
        List<String> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).isNotEmpty().allMatch(w -> w.startsWith("Request logging dropped ")
                && w.endsWith(" entries (the oldest first when writing falls behind; buffer limit 5 entries / 1 MB). "
                        + "Mock responses were not delayed"));
        int reported = warnings.stream()
                .mapToInt(w -> Integer.parseInt(w.replaceAll("^Request logging dropped (\\d+) entries.*$", "$1")))
                .sum();
        // 上限 5 筆含寫入中的 1 筆：12 筆裡只留得下最新 4 筆
        assertThat(reported).isEqualTo(8);
    }
}
