package com.echo.integration.http;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.pipeline.MockRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Real Brave propagation/export lifecycle, not a mock of the Observation API. */
@AutoConfigureObservability
@Import(NativeHttpTracingIntegrationTest.Configuration.class)
@TestPropertySource(properties = {
        "echo.http.transport=native", "echo.http.native.workers=1",
        "echo.http.request-timeout-ms=500", "echo.jms.enabled=false",
        "management.tracing.sampling.probability=1.0",
        "management.tracing.propagation.consume=W3C", "management.tracing.propagation.produce=W3C"
})
class NativeHttpTracingIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired Tracer tracer;
    @Autowired RecordedSpans recorded;
    @Autowired MeterRegistry meters;
    @MockitoSpyBean HttpMockPipeline pipeline;
    private final ConcurrentHashMap<String, String> pipelineTraces = new ConcurrentHashMap<>();

    @BeforeEach
    void observeActualWorkerScope() {
        doAnswer(invocation -> {
            MockRequest request = invocation.getArgument(0);
            var current = tracer.currentSpan();
            pipelineTraces.put(request.getPath(), current == null ? "absent" : current.context().traceId());
            return invocation.callRealMethod();
        }).when(pipeline).executeAsync(any(MockRequest.class));
    }

    @Test
    void parentPropagationIncludesWorkerAndDelayWithoutDuplicateTimersOrLeakedScope() throws Exception {
        createHttpRule("/trace-immediate", "GET", "immediate");
        createHttpRule("/trace-delayed", "GET", "delayed", null, 200,
                null, null, null, null, 80L, null, null);
        createHttpRule("/trace-root", "GET", "root");
        String traceId = "0123456789abcdef0123456789abcdef";
        String parentId = "1122334455667788";
        long countBefore = timedRequests();
        HttpClient client = HttpClient.newHttpClient();
        for (String path : List.of("/trace-immediate", "/trace-delayed")) {
            var response = client.send(request(path).header("traceparent", "00-" + traceId + "-" + parentId + "-01")
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(pipelineTraces.get(path)).isEqualTo(traceId);
        }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var spans = recorded.spans.stream().filter(s -> s.traceId().equals(traceId)).toList();
            assertThat(spans).hasSize(2).allSatisfy(s -> {
                assertThat(s.parentId()).isEqualTo(parentId);
                assertThat(s.error()).isNull();
                assertThat(s.uri()).isEqualTo("/mock/**");
            });
            assertThat(spans.stream().mapToLong(Finished::durationMicros).max().orElseThrow()).isGreaterThanOrEqualTo(75_000);
            assertThat(timedRequests() - countBefore).isEqualTo(2);
        });
        // The same single worker must not inherit either request's remote parent.
        assertThat(client.send(request("/trace-root").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(pipelineTraces.get("/trace-root")).isNotEqualTo(traceId).isNotEqualTo("absent");
    }

    @Test
    void timeoutEndsOneServerSpanWithTheRealErrorAndReleasesTheRequest() throws Exception {
        createHttpRule("/trace-timeout", "GET", "late", null, 200,
                null, null, null, null, 10_000L, null, null);
        String traceId = "fedcba9876543210fedcba9876543210";
        var response = HttpClient.newHttpClient().send(request("/trace-timeout")
                .header("traceparent", "00-" + traceId + "-8877665544332211-01").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(504);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var spans = recorded.spans.stream().filter(s -> s.traceId().equals(traceId)).toList();
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).error()).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(spans.get(0).status()).isEqualTo("504");
            assertThat(meters.find("echo.http.native.active").gauge().value()).isZero();
        });
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mock" + path))
                .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(5));
    }

    private long timedRequests() {
        return meters.find("http.server.requests").tag("uri", "/mock/**").timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    record Finished(String traceId, String parentId, String uri, String status, Throwable error, long durationMicros) {}

    static final class RecordedSpans extends SpanHandler {
        final List<Finished> spans = new CopyOnWriteArrayList<>();

        @Override
        public boolean end(TraceContext context, MutableSpan span, Cause cause) {
            if (span.kind() == brave.Span.Kind.SERVER) {
                spans.add(new Finished(context.traceIdString(), context.parentIdString(), span.tag("uri"),
                        span.tag("status"), span.error(), span.finishTimestamp() - span.startTimestamp()));
            }
            return true;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean RecordedSpans recordedSpans() { return new RecordedSpans(); }
    }
}
