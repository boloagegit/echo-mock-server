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
    @DisplayName("超過筆數上限：丟掉最舊的，保留最新的")
    void dropsTheOldestOverTheEntryLimit() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        handoff = new RequestLogHandoff(10, 1 << 20, blockedUntil(gate, entered), 10, System::nanoTime);
        handoff.start();
        handoff.offer(task("/first"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // 寫入端忙著寫 /first 時又來 25 筆：只留最新 10 筆
        for (int i = 0; i < 25; i++) {
            handoff.offer(task("/" + i));
        }
        assertThat(handoff.size()).isEqualTo(10);
        gate.countDown();

        assertThat(handoff.awaitIdle(5_000)).isTrue();
        assertThat(written).containsExactly("/first", "/15", "/16", "/17", "/18", "/19", "/20", "/21", "/22",
                "/23", "/24");
        assertThat(handoff.droppedCount()).isEqualTo(15);
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
        assertThat(written).containsExactly("/first", "/2", "/3", "/4");
        assertThat(handoff.droppedCount()).isEqualTo(3);
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
        assertThat(handoff.size()).isEqualTo(5);

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
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Request logs arrived faster than they could be written; dropped 7 of the "
                        + "oldest entries (buffer limit 5 entries / 1 MB). Mock responses were not delayed");
    }
}
