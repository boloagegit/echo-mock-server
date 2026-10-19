package com.echo.integration.http;

import com.echo.integration.base.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Live-port coverage for bulk reads, including fragmented unknown-length uploads. */
@TestPropertySource(properties = {
        "echo.http.transport=native", "echo.http.request-timeout-ms=5000",
        "echo.http.native.max-body-bytes=131072", "echo.http.native.max-buffered-bytes=1048576"
})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class NativeHttpBodyIntegrationTest extends BaseIntegrationTest {
    private static final int LIMIT = 131072;
    @LocalServerPort int port;
    @Autowired MeterRegistry meters;

    static Stream<org.junit.jupiter.params.provider.Arguments> encodings() {
        return Stream.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE)
                .flatMap(charset -> Stream.of(false, true)
                        .map(chunked -> org.junit.jupiter.params.provider.Arguments.of(charset, chunked)));
    }

    @ParameterizedTest
    @MethodSource("encodings")
    void largeFragmentedBodyRetainsEveryCharacter(Charset charset, boolean chunked) throws Exception {
        createHttpRule("/bulk-body", "POST", "{{{request.body}}}");
        String expected = "中文🙂é".repeat(7000);
        byte[] encoded = expected.getBytes(charset);
        assertThat(encoded.length).isBetween(65536, LIMIT);
        double before = nativeRequests();
        var response = send(encoded, charset, chunked);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expected);
        assertThat(nativeRequests()).isEqualTo(before + 1);
        assertReleased();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactLimitSucceedsAndCumulativeOverflowRejectsThenRecovers(boolean chunked) throws Exception {
        createHttpRule("/bulk-body", "POST", "accepted");
        byte[] atLimit = "x".repeat(LIMIT).getBytes(StandardCharsets.UTF_8);
        assertThat(send(atLimit, StandardCharsets.UTF_8, chunked).statusCode()).isEqualTo(200);
        assertReleased();
        assertThat(send(java.util.Arrays.copyOf(atLimit, LIMIT + 1), StandardCharsets.UTF_8, chunked).statusCode())
                .isEqualTo(413);
        assertReleased();
        assertThat(send(new byte[]{'x'}, StandardCharsets.UTF_8, false).body()).isEqualTo("accepted");
        assertReleased();
    }

    @Test
    void emptyUnknownLengthUploadPreservesEmptyBody() throws Exception {
        createHttpRule("/bulk-body", "POST", "{{{request.body}}}");
        var response = send(new byte[0], StandardCharsets.UTF_8, true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
        assertReleased();
    }

    private HttpResponse<String> send(byte[] encoded, Charset charset, boolean chunked) throws Exception {
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(encoded) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                // Force many publisher chunks, including splits inside UTF-8/UTF-16 characters.
                return super.read(bytes, offset, Math.min(length, 257));
            }
        }) : HttpRequest.BodyPublishers.ofByteArray(encoded);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/bulk-body"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "text/plain;charset=" + charset.name())
                // Declared oversize is rejected before reading. Wait for that response
                // instead of racing the deliberate close with unsent bytes on Windows.
                // Unknown-length uploads still send the body to test the cumulative limit.
                .expectContinue(!chunked && encoded.length > LIMIT)
                .POST(publisher).build();
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private double nativeRequests() {
        var counter = meters.find("echo.http.native.requests").tag("mode", "native").counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertReleased() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            for (String name : new String[]{"echo.http.native.active", "echo.http.native.buffered.bytes", "echo.http.native.queued"}) {
                assertThat(meters.find(name).gauge().value()).as(name).isZero();
            }
        });
    }
}
