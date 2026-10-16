package com.echo.protocol.http.sse;

import com.echo.diagnostics.TransactionDiagnostics.Trace;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 客戶端連著但不讀資料時，不能拖累其他串流；卡住的寫入在逾時後關閉連線。 */
class SseStalledClientTest {

    private static final SsePlayer.RequestContext REQUEST =
            new SsePlayer.RequestContext(null, Map.of(), "/sse", "GET", null);

    @Test
    void clientsThatNeverReadDoNotStopOtherStreams() throws Exception {
        SsePlayer player = new SsePlayer(null, SsePlayer.DEFAULT_STALLED_WRITE_TIMEOUT_MS);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(12);
        try {
            // 比原本 8 條排程執行緒更多的卡住寫入
            for (int i = 0; i < 12; i++) {
                SseEmitter emitter = mock(SseEmitter.class);
                doAnswer(call -> { blocked.countDown(); release.await(10, TimeUnit.SECONDS); return null; })
                        .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
                var unused = player.play(emitter, List.of(new SsePlayer.SseEvent(null, "big", null, 0L, null)),
                        false, REQUEST, 0, Trace.NONE);
            }
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();

            SseEmitter healthy = mock(SseEmitter.class);
            AtomicInteger sent = new AtomicInteger();
            doAnswer(call -> { sent.incrementAndGet(); return null; }).when(healthy).send(any(SseEmitter.SseEventBuilder.class));
            CompletableFuture<Void> done = player.play(healthy, List.of(
                            new SsePlayer.SseEvent(null, "a", null, 10L, null),
                            new SsePlayer.SseEvent(null, "b", null, 10L, null),
                            new SsePlayer.SseEvent(null, "c", null, 10L, null)),
                    false, REQUEST, 0, Trace.NONE);

            done.get(3, TimeUnit.SECONDS);
            assertThat(sent).hasValue(3);
            verify(healthy).complete();
        } finally {
            release.countDown();
            player.shutdown();
        }
    }

    @Test
    void stalledWriteClosesOnlyThatConnection() throws Exception {
        SsePlayer player = new SsePlayer(null, 1_000);
        CountDownLatch closed = new CountDownLatch(1);
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(call -> {
            // 跟真實連線一樣：連線被關閉後，卡住的寫入才以 IOException 結束
            if (!closed.await(10, TimeUnit.SECONDS)) throw new AssertionError("connection was not closed");
            throw new IOException("connection closed");
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        try {
            long start = System.nanoTime();
            CompletableFuture<Void> done = player.play(emitter, List.of(new SsePlayer.SseEvent(null, "x", null, 0L, null)),
                    true, REQUEST, 0, Trace.NONE, closed::countDown);

            done.get(5, TimeUnit.SECONDS);
            assertThat(closed.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
            verify(emitter, never()).complete();
        } finally {
            player.shutdown();
        }
    }

    @Test
    void longPausesBetweenEventsAreNotTreatedAsStalled() throws Exception {
        SsePlayer player = new SsePlayer(null, 1_000);
        AtomicInteger closes = new AtomicInteger();
        SseEmitter emitter = mock(SseEmitter.class);
        try {
            CompletableFuture<Void> done = player.play(emitter, List.of(
                            new SsePlayer.SseEvent(null, "first", null, 0L, null),
                            new SsePlayer.SseEvent(null, "second", null, 2_500L, null)),
                    false, REQUEST, 0, Trace.NONE, closes::incrementAndGet);

            done.get(6, TimeUnit.SECONDS);
            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter).complete();
            assertThat(closes).hasValue(0);
        } finally {
            player.shutdown();
        }
    }

    @Test
    void busyWritersDelayAStreamInsteadOfStoppingIt() throws Exception {
        var real = Executors.newCachedThreadPool();
        AtomicInteger rejections = new AtomicInteger();
        Executor writer = task -> {
            if (rejections.incrementAndGet() <= 3) throw new RejectedExecutionException("all writers busy");
            real.execute(task);
        };
        SsePlayer player = new SsePlayer(null, null, writer, SsePlayer.DEFAULT_STALLED_WRITE_TIMEOUT_MS);
        SseEmitter emitter = mock(SseEmitter.class);
        try {
            CompletableFuture<Void> done = player.play(emitter, List.of(new SsePlayer.SseEvent(null, "x", null, 0L, null)),
                    false, REQUEST, 0, Trace.NONE);

            done.get(5, TimeUnit.SECONDS);
            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter).complete();
            assertThat(rejections.get()).isGreaterThanOrEqualTo(4);
        } finally {
            player.shutdown();
            real.shutdownNow();
        }
    }

    @Test
    void stalledTimeoutBelowOneSecondIsRaisedToOneSecond() throws Exception {
        SsePlayer player = new SsePlayer(null, 100);
        CountDownLatch closed = new CountDownLatch(1);
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(call -> {
            if (!closed.await(10, TimeUnit.SECONDS)) throw new AssertionError("connection was not closed");
            throw new IOException("connection closed");
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        try {
            long start = System.nanoTime();
            player.play(emitter, List.of(new SsePlayer.SseEvent(null, "x", null, 0L, null)),
                    false, REQUEST, 0, Trace.NONE, closed::countDown).get(5, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        } finally {
            player.shutdown();
        }
    }

    @Test
    void watchdogIgnoresStreamsWithoutAWriteInProgress() {
        SsePlayer player = new SsePlayer(null, 1_000);
        try {
            await().pollDelay(Duration.ofMillis(10)).atMost(Duration.ofSeconds(1)).until(() -> {
                player.closeStalledWrites();
                return true;
            });
        } finally {
            player.shutdown();
        }
    }

    @Test
    void streamWithoutCloseCallbackIsStoppedWithoutBlockingTheWatchdog() throws Exception {
        SsePlayer player = new SsePlayer(null, 1_000);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        SseEmitter withoutCallback = mock(SseEmitter.class);
        doAnswer(call -> { releaseFirst.await(10, TimeUnit.SECONDS); return null; })
                .when(withoutCallback).send(any(SseEmitter.SseEventBuilder.class));
        CountDownLatch closed = new CountDownLatch(1);
        SseEmitter withCallback = mock(SseEmitter.class);
        doAnswer(call -> {
            if (!closed.await(10, TimeUnit.SECONDS)) throw new AssertionError("connection was not closed");
            throw new IOException("connection closed");
        }).when(withCallback).send(any(SseEmitter.SseEventBuilder.class));
        try {
            CompletableFuture<Void> first = player.play(withoutCallback,
                    List.of(new SsePlayer.SseEvent(null, "x", null, 0L, null)), true, REQUEST, 0, Trace.NONE);
            CompletableFuture<Void> second = player.play(withCallback,
                    List.of(new SsePlayer.SseEvent(null, "y", null, 0L, null)), true, REQUEST, 0, Trace.NONE, closed::countDown);

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(closed.getCount()).isZero();
            verify(withoutCallback, never()).completeWithError(any());
        } finally {
            releaseFirst.countDown();
            player.shutdown();
        }
    }
}
