package com.echo.config;

import com.echo.dto.HttpTargetConnectionRequest;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.repository.HttpTargetConnectionRepository;
import com.echo.service.HttpTargetConnectionService;
import com.echo.service.ServeEvent;
import com.echo.service.ServeEventListener;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"echo.http.transport=native", "echo.jms.enabled=false",
        "echo.http.native.max-body-bytes=524288", "echo.http.native.max-buffered-bytes=8388608",
        "echo.http.forward.max-response-body-bytes=524288"})
class NativeUtf8ForwardingIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort int port;
    @Autowired HttpMockPipeline pipeline;
    @Autowired NativeHttpResources resources;
    @Autowired HttpTargetConnectionService targets;
    @Autowired HttpTargetConnectionRepository targetRepository;

    @Test
    void byteAndLegacyPathsHaveIdenticalWireBodiesHeadersAndLimitFailures() throws Exception {
        // Fixture arrays are privately created here; record equality is never used.
        @SuppressWarnings("ArrayRecordComponent")
        record Payload(byte[] bytes, Charset charset, boolean segmented, int status) {}
        byte[] malformed = ("r".repeat(70_000) + "銀行😀").getBytes(StandardCharsets.UTF_8);
        malformed[malformed.length - 1] = (byte) 0xff;
        List<Payload> cases = List.of(
                new Payload(" \u0000\t{".concat("r".repeat(100_000)).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, true, 202),
                new Payload((" \n<xml>" + "銀行😀".repeat(20_000)).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, true, 200),
                new Payload(malformed, StandardCharsets.UTF_8, false, 200),
                new Payload("銀行é".repeat(20_000).getBytes(StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE, false, 200),
                new Payload("café".repeat(20_000).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1, false, 200),
                new Payload(new byte[0], StandardCharsets.UTF_8, false, 204),
                new Payload(new byte[0], StandardCharsets.UTF_8, false, 304),
                new Payload(new byte[524288], StandardCharsets.UTF_8, true, 200),
                new Payload(new byte[524289], StandardCharsets.UTF_8, false, 502));
        AtomicReference<Payload> current = new AtomicReference<>();
        AtomicBoolean requireFull = new AtomicBoolean();
        AtomicReference<Boolean> observed = new AtomicReference<>();
        pipeline.setServeEventListeners(List.of(new ServeEventListener() {
            @Override public boolean requiresFullResponseBody() { return requireFull.get(); }
            @Override public void onServed(ServeEvent event) { observed.set(event.getUtf8ResponseBody() != null); }
        }));
        HttpServer downstream = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        downstream.createContext("/", exchange -> {
            var payload = current.get();
            exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=" + payload.charset().name());
            exchange.sendResponseHeaders(payload.status() == 502 ? 200 : payload.status(), 0);
            try (var output = exchange.getResponseBody()) {
                for (int offset = 0; offset < payload.bytes().length; offset += 8191) {
                    output.write(payload.bytes(), offset, Math.min(8191, payload.bytes().length - offset));
                    output.flush();
                }
            } finally { exchange.close(); }
        });
        downstream.start();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try {
            targets.create(new HttpTargetConnectionRequest(null, "UTF-8 wire parity",
                    "http://127.0.0.1:" + downstream.getAddress().getPort(), "NONE", null, null,
                    false, 1, 3, true, true, true));
            for (var payload : cases) {
                current.set(payload);
                HttpResponse<byte[]> baseline = null;
                for (boolean full : new boolean[]{true, false}) {
                    requireFull.set(full);
                    observed.set(null);
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/bytes"))
                            .timeout(Duration.ofSeconds(5)).build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    assertThat(response.statusCode()).isEqualTo(payload.status());
                    if (payload.status() != 502) {
                        assertThat(response.body()).isEqualTo(new String(payload.bytes(), payload.charset()).getBytes(StandardCharsets.UTF_8));
                    }
                    assertThat(observed.get()).isEqualTo(!full && payload.segmented());
                    if (baseline == null) baseline = response;
                    else {
                        assertThat(response.body()).isEqualTo(baseline.body());
                        for (String name : new String[]{"Content-Type", "Content-Length", "Transfer-Encoding"}) {
                            assertThat(response.headers().allValues(name)).as(name).isEqualTo(baseline.headers().allValues(name));
                        }
                    }
                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                        assertThat(resources.active()).isZero();
                        assertThat(resources.bufferedBytes()).isZero();
                    });
                }
            }
        } finally {
            pipeline.setServeEventListeners(List.of());
            targetRepository.deleteAll();
            downstream.stop(0);
        }
    }
}
