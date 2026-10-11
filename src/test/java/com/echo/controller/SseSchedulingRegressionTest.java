package com.echo.controller;

import com.echo.diagnostics.TransactionDiagnostics.Trace;
import com.echo.protocol.http.sse.SseMockHandler;
import com.echo.protocol.http.sse.SsePlayer;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SSE 播放使用獨立的 scheduler，被阻塞的 SSE 寫入不會占用一般 HTTP 延遲回應的 worker。 */
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
        var player = new SsePlayer(mock(com.echo.service.ResponseTemplateService.class));
        var controller = new UniversalMockController(pipeline,
                new SseMockHandler(null, null, null, null, pipeline, player));
        try {
            for (int i = 0; i < 8; i++) {
                SseEmitter emitter = mock(SseEmitter.class);
                doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; })
                        .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
                playbacks.add(player.play(emitter, List.of(
                        new SsePlayer.SseEvent(null, "blocked", null, 0L, null)),
                        false, new SsePlayer.RequestContext(null, Map.of(), "/slow", "GET", null), 0, Trace.NONE));
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
            player.shutdown();
            controller.shutdown();
            for (var playback : playbacks) playback.get(1, TimeUnit.SECONDS);
        }
    }
}
