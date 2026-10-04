package com.echo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Forces delivery and cancellation before schedule() publishes its returned future. */
class SseSchedulingRegressionTest {
    @Test
    void eightBlockedSseWritersDoNotBlockGeneralHttpDelayWorkers() throws Exception {
        var pipeline = mock(com.echo.pipeline.HttpMockPipeline.class);
        when(pipeline.executeAsync(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                com.echo.pipeline.PipelineResult.builder().delayMs(20)
                        .response(com.echo.pipeline.MockResponse.builder().status(200).body("ordinary").build()).build()));
        var entered = new java.util.concurrent.CountDownLatch(8);
        var release = new java.util.concurrent.CountDownLatch(1);
        var playbacks = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        var controller = new UniversalMockController(null, null, null,
                mock(com.echo.service.ResponseTemplateService.class), pipeline);
        try {
            for (int i = 0; i < 8; i++) {
                SseEmitter emitter = mock(SseEmitter.class);
                doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; })
                        .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
                playbacks.add(controller.startSsePlayback(emitter, List.of(
                        new UniversalMockController.SseEvent(null, "blocked", null, 0L, null)),
                        false, null, Map.of(), "/slow", "GET"));
            }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var result = (org.springframework.web.context.request.async.DeferredResult<?>) controller.handleRequest(
                    new org.springframework.mock.web.MockHttpServletRequest("GET", "/mock/general"),
                    new org.springframework.mock.web.MockHttpServletResponse(), null);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(result.getResult()).isInstanceOf(org.springframework.http.ResponseEntity.class));
            assertThat(((org.springframework.http.ResponseEntity<?>) result.getResult()).getBody()).isEqualTo("ordinary");
        } finally {
            release.countDown();
            controller.shutdown();
            for (var playback : playbacks) playback.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void immediateDeliveryBeforeScheduleReturnsCannotCancelTheNextEvent() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> first = mock(ScheduledFuture.class), second = mock(ScheduledFuture.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Runnable> next = new AtomicReference<>();
        doAnswer(call -> {
            Runnable task = call.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                // Nested schedule of event two happens before event one's schedule returns.
                task.run();
                return first;
            }
            next.set(task);
            return second;
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        SseEmitter emitter = mock(SseEmitter.class);
        var controller = new UniversalMockController(null, null, null,
                mock(com.echo.service.ResponseTemplateService.class), null, scheduler);
        try {
            var completion = controller.startSsePlayback(emitter, List.of(
                    new UniversalMockController.SseEvent(null, "first", null, 0L, null),
                    new UniversalMockController.SseEvent(null, "second", null, 100L, null)),
                    false, null, Map.of(), "/race", "GET");
            assertThat(calls).hasValue(2);
            assertThat(completion).isNotDone();
            verify(second, never()).cancel(anyBoolean());
            next.get().run();
            completion.get(1, TimeUnit.SECONDS);
            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter).complete();
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void timeoutBeforeFuturePublicationStillCancelsThatFuture() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicReference<Runnable> timeout = new AtomicReference<>();
        doAnswer(call -> { timeout.set(call.getArgument(0)); return null; }).when(emitter).onTimeout(any());
        doAnswer(call -> { timeout.get().run(); return future; })
                .when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        var controller = new UniversalMockController(null, null, null, null, null, scheduler);
        try {
            var completion = controller.startSsePlayback(emitter, List.of(
                    new UniversalMockController.SseEvent(null, "later", null, 30_000L, null)),
                    true, null, Map.of(), "/timeout", "GET");
            assertThat(completion).isCompleted();
            verify(future).cancel(false);
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void terminalEventsStopLoopWithoutAnyClientCompletionCallback() throws Exception {
        for (String type : List.of("error", "abort")) {
            SseEmitter emitter = mock(SseEmitter.class);
            var controller = new UniversalMockController(null, null, null,
                    mock(com.echo.service.ResponseTemplateService.class), null);
            try {
                var completion = controller.startSsePlayback(emitter, List.of(
                        new UniversalMockController.SseEvent(null, "first", null, 0L, null),
                        new UniversalMockController.SseEvent(null, "terminal", null, 0L, type),
                        new UniversalMockController.SseEvent(null, "forbidden", null, 30_000L, null)),
                        true, null, Map.of(), "/loop", "GET");
                completion.get(1, TimeUnit.SECONDS);
                verify(emitter, times(type.equals("error") ? 2 : 1)).send(any(SseEmitter.SseEventBuilder.class));
                verify(emitter).completeWithError(any(RuntimeException.class));
                verify(emitter, never()).complete();
                assertThat(controller.getPendingSseTaskCount()).isZero();
            } finally {
                controller.shutdown();
            }
        }
    }

    @Test
    void disconnectAndAllLifecycleCallbacksStopLoopAndRemoveQueuedFuture() throws Exception {
        for (String terminal : List.of("completion", "timeout", "error", "disconnect", "shutdown")) {
            SseEmitter emitter = mock(SseEmitter.class);
            AtomicReference<Runnable> finish = new AtomicReference<>();
            if (terminal.equals("completion")) doAnswer(c -> { finish.set(c.getArgument(0)); return null; }).when(emitter).onCompletion(any());
            if (terminal.equals("timeout")) doAnswer(c -> { finish.set(c.getArgument(0)); return null; }).when(emitter).onTimeout(any());
            if (terminal.equals("error")) doAnswer(c -> {
                java.util.function.Consumer<Throwable> callback = c.getArgument(0);
                finish.set(() -> callback.accept(new IOException("synthetic disconnect"))); return null;
            }).when(emitter).onError(any());
            var scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
            scheduler.setRemoveOnCancelPolicy(true);
            var controller = new UniversalMockController(null, null, null,
                    mock(com.echo.service.ResponseTemplateService.class), null, scheduler);
            try {
                List<UniversalMockController.SseEvent> events = List.of(
                        new UniversalMockController.SseEvent(null, "wait", null, 30_000L, null));
                if (terminal.equals("disconnect")) {
                    doThrow(new IOException("synthetic disconnect")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
                    events = List.of(new UniversalMockController.SseEvent(null, "now", null, 0L, null));
                }
                var completion = controller.startSsePlayback(emitter, events, true, null, Map.of(), "/loop", "GET");
                if (terminal.equals("shutdown")) controller.shutdown();
                else if (!terminal.equals("disconnect")) finish.get().run();
                completion.get(1, TimeUnit.SECONDS);
                assertThat(controller.getPendingSseTaskCount()).as(terminal).isZero();
            } finally {
                controller.shutdown();
            }
        }
    }
}
