package com.echo.integration.http;

import com.echo.integration.base.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {
        "echo.http.transport=native", "echo.http.request-timeout-ms=1000",
        "echo.http.native.workers=2", "echo.http.native.max-requests=2",
        "echo.http.native.max-body-bytes=4096", "echo.http.native.max-buffered-bytes=16384"
})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class NativeHttpCapacityIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired MeterRegistry meters;

    @Test
    void saturationRejectsExtraWorkAndRecoversAfterDelayedRequestsFinish() throws Exception {
        createHttpRule("/held", "GET", "done", null, 200, null, null, null, null, 500L, null, null);
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var one = client.sendAsync(get("/held"), HttpResponse.BodyHandlers.ofString());
        var two = client.sendAsync(get("/held"), HttpResponse.BodyHandlers.ofString());
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(5))
                .untilAsserted(() -> assertThat(active()).isEqualTo(2));
        var rejected = client.send(get("/held"), HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(one.get(3, TimeUnit.SECONDS).body()).isEqualTo("done");
        assertThat(two.get(3, TimeUnit.SECONDS).body()).isEqualTo("done");
        assertReleased();
        assertThat(client.send(get("/held"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertReleased();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void declaredAndChunkedOversizedBodiesAreRejectedAndReleaseReservations(boolean chunked) throws Exception {
        createHttpRule("/body", "POST", "ok");
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        byte[] body = new byte[5000];
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        var response = client.send(HttpRequest.newBuilder(uri("/body"))
                .timeout(Duration.ofSeconds(3)).POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertReleased();
        assertThat(restTemplate.postForEntity("/mock/body", "small", String.class).getBody()).isEqualTo("ok");
        assertReleased();
    }

    @Test
    void aggregateBufferLimitRejectsBeforeReadingAnotherBodyAndRecovers() throws Exception {
        createHttpRule("/buffer", "POST", "ok");
        try (var socket = new Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            socket.getOutputStream().write(("POST /mock/buffer HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 3000\r\n\r\nx").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(5)).untilAsserted(() ->
                    assertThat(meters.find("echo.http.native.buffered.bytes").gauge().value()).isEqualTo(12000));
            var request = HttpRequest.newBuilder(uri("/buffer")).timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(2000))).build();
            assertThat(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(503);
        }
        assertReleased();
        assertThat(restTemplate.postForEntity("/mock/buffer", "small", String.class).getBody()).isEqualTo("ok");
        assertReleased();
    }

    @Test
    void bodyLimitDoesNotTurnConnectionResetIntoAnHttpErrorResponse() throws Exception {
        var rule = createHttpRule("/reset", "GET", "x".repeat(5000));
        rule.setFaultType("CONNECTION_RESET");
        var updated = adminClient().exchange("/api/admin/rules/" + rule.getId(), org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(rule), String.class);
        assertThat(updated.getStatusCode().is2xxSuccessful()).isTrue();
        try (var socket = new Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write("GET /mock/reset HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            try { assertThat(socket.getInputStream().read()).isEqualTo(-1); }
            catch (java.net.SocketException reset) { /* Both FIN and RST are valid transport closure. */ }
        }
        assertReleased();
    }

    @Test
    void responseLimitCountsEncodedUtf8BytesAndRecovers() {
        createHttpRule("/wide", "GET", "漢".repeat(2000));
        assertThat(restTemplate.getForEntity("/mock/wide", String.class).getStatusCode().value()).isEqualTo(502);
        assertReleased();
        createHttpRule("/fine", "GET", "ok");
        assertThat(restTemplate.getForObject("/mock/fine", String.class)).isEqualTo("ok");
        assertReleased();
    }

    @Test
    void stalledUploadHasADeadlineAndDoesNotKeepAWorkerOrReservation() throws Exception {
        createHttpRule("/upload", "POST", "ok");
        try (var socket = new Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("POST /mock/upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 1000\r\nContent-Type: text/plain\r\n\r\nx").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        }
        assertReleased();
        assertThat(restTemplate.postForEntity("/mock/upload", "small", String.class).getBody()).isEqualTo("ok");
        assertReleased();
    }

    @Test
    void disconnectedDelayedRequestEventuallyReleasesAllResources() throws Exception {
        createHttpRule("/disconnected", "GET", "late", null, 200, null, null, null, null, 10000L, null, null);
        try (var socket = new Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            socket.getOutputStream().write("GET /mock/disconnected HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(active()).isEqualTo(1));
        }
        assertReleased();
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/mock" + path); }
    private HttpRequest get(String path) { return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(3)).build(); }
    private double active() {
        var gauge = meters.find("echo.http.native.active").gauge();
        return gauge == null ? 0 : gauge.value();
    }
    private void assertReleased() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(active()).isZero();
            assertThat(meters.find("echo.http.native.buffered.bytes").gauge().value()).isZero();
            assertThat(meters.find("echo.http.native.queued").gauge().value()).isZero();
        });
    }
}
