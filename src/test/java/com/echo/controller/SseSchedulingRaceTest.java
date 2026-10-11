package com.echo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.echo.service.ResponseTemplateService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SseSchedulingRaceTest {
    @Test
    void earlyFirstEventCannotCancelItsDelayedSuccessor() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicReference<Runnable> timeout = new AtomicReference<>();
        doAnswer(call -> { timeout.set(call.getArgument(0)); return null; }).when(emitter).onTimeout(any());
        ScheduledFuture<?> first = mock(ScheduledFuture.class);
        ScheduledFuture<?> second = mock(ScheduledFuture.class);
        AtomicReference<Runnable> successor = new AtomicReference<>();
        CountDownLatch firstReturned = new CountDownLatch(1);
        UniversalMockController controller = new UniversalMockController(null, null, null,
                mock(ResponseTemplateService.class), null) {
            private int calls;
            @Override ScheduledFuture<?> scheduleSseEvent(Runnable delivery, long delayMs) {
                if (calls++ == 0) {
                    // Legal scheduler interleaving: the callback runs before schedule returns.
                    delivery.run();
                    firstReturned.countDown();
                    return first;
                }
                assertThat(delayMs).isEqualTo(500);
                successor.set(delivery);
                return second;
            }
        };
        AtomicReference<Thread> worker = new AtomicReference<>();
        CompletableFuture<Void> playback = CompletableFuture.runAsync(() -> {
            worker.set(Thread.currentThread());
            controller.sendSseEvents(emitter, List.of(new UniversalMockController.SseEvent(null, "fast", null, 0L, null),
                        new UniversalMockController.SseEvent(null, "delayed", null, 500L, null)),
                false, null, Map.of(), "/race", "GET");
        });
        try {
            assertThat(firstReturned.await(2, TimeUnit.SECONDS)).isTrue();
            // Wait for the caller to publish the first future, not merely its callback.
            await().atMost(2, TimeUnit.SECONDS).until(() -> worker.get().getState() == Thread.State.WAITING);
            verify(second, never()).cancel(false);
            // Drive the retained successor only after the first schedule call has unwound.
            successor.get().run();
            playback.get(2, TimeUnit.SECONDS);
            verify(second, never()).cancel(false);
            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter).complete();
        } finally {
            if (timeout.get() != null) timeout.get().run();
            controller.shutdown();
        }
    }

    @Test
    void timeoutBeforeFutureRegistrationCancelsLateFuture() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicReference<Runnable> timeout = new AtomicReference<>();
        doAnswer(call -> { timeout.set(call.getArgument(0)); return null; }).when(emitter).onTimeout(any());
        ScheduledFuture<?> late = mock(ScheduledFuture.class);
        UniversalMockController controller = new UniversalMockController(null, null, null, null, null) {
            @Override ScheduledFuture<?> scheduleSseEvent(Runnable delivery, long delayMs) {
                timeout.get().run();
                return late;
            }
        };
        try {
            controller.sendSseEvents(emitter,
                    List.of(new UniversalMockController.SseEvent(null, "later", null, 500L, null)),
                    false, null, Map.of(), "/race", "GET");
            verify(late).cancel(false);
            verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            controller.shutdown();
        }
    }
}
