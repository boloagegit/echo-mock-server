package com.echo.integration.http;

import com.echo.dto.HttpTargetConnectionRequest;
import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.HttpTargetConnectionRepository;
import com.echo.service.HttpTargetConnectionService;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Only synthetic random-port loopback servers receive forwarded requests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "echo.jms.enabled=false", "echo.jms.target.enabled=false",
        "echo.ldap.enabled=false", "echo.backup.enabled=false", "echo.cleanup.enabled=false"})
@Timeout(30)
class SseForwardingRegressionIntegrationTest extends BaseIntegrationTest {
    private static final List<String> METHODS = List.of("POST", "PUT", "PATCH", "DELETE");
    private static final String BODY = "{\"prompt\":\"synthetic 中文😀\"}";

    @LocalServerPort private int port;
    @Autowired private HttpTargetConnectionService connections;
    @Autowired private HttpTargetConnectionRepository connectionRepository;
    @TempDir private Path tempDirectory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger downstreamRequests = new AtomicInteger();
    private final AtomicReference<ReceivedRequest> received = new AtomicReference<>();
    private HttpServer downstream;

    @BeforeEach
    void startDownstream() throws Exception {
        assertThat(connectionRepository.count()).isZero();
        downstream = HttpServer.create(loopbackAddress(), 0);
        registerDownstream(downstream);
        downstream.start();
    }

    @AfterEach
    void stopDownstream() {
        if (downstream != null) downstream.stop(0);
        connectionRepository.deleteAll();
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    private InetSocketAddress loopbackAddress() throws Exception {
        return new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
    }

    private void registerDownstream(HttpServer server) {
        server.createContext("/", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.set(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("Accept"), body,
                        exchange.getRequestHeaders().getFirst("X-Original-Host")));
                downstreamRequests.incrementAndGet();
                byte[] reply = ("synthetic downstream: " + body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=UTF-8");
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(202, -1);
                } else {
                    exchange.sendResponseHeaders(202, reply.length);
                    exchange.getResponseBody().write(reply);
                }
            }
        });
    }

    private void configureDefaultConnection() {
        connections.create(new HttpTargetConnectionRequest(null, "synthetic-loopback",
                "http://127.0.0.1:" + downstream.getAddress().getPort() + "/base", "NONE", null, null,
                false, 2, 5, false, true, true));
    }

    private HttpRequest.Builder request(String path, String method) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock" + path))
                .header("Accept", "text/event-stream").header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.ofString(BODY));
    }

    private void assertForwarded(HttpResponse<String> response, String method, String path, int count) {
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("synthetic downstream: " + BODY);
        assertThat(response.headers().firstValue("content-type").orElse("")).doesNotContain("text/event-stream");
        assertThat(received.get()).isEqualTo(new ReceivedRequest(method, path, "text/event-stream", BODY, null));
        assertThat(downstreamRequests.get()).isEqualTo(count);
    }

    @Test
    void unmatchedSseAcceptWithBodyUsesDefaultConnectionBeforeOriginalHost() throws Exception {
        configureDefaultConnection();
        int count = 0;
        for (String method : METHODS) {
            var response = client.send(request("/unmatched?source=synthetic", method)
                    // A legacy HTTPS attempt at this plain HTTP port would fail; the default must win.
                    .header("X-Original-Host", "127.0.0.1:" + downstream.getAddress().getPort()).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertForwarded(response, method, "/base/unmatched?source=synthetic", ++count);
        }
    }

    @Test
    void unmatchedSseAcceptWithBodyAndNoForwardingTargetReturns404() throws Exception {
        for (String method : METHODS) {
            var response = client.send(request("/unmatched", method).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).contains("No mock rule found");
        }
        assertThat(downstreamRequests.get()).isZero();
    }

    @Test
    void unmatchedSseGetAndHeadKeep404DespiteDefaultConnection() throws Exception {
        configureDefaultConnection();
        for (String method : List.of("GET", "HEAD")) {
            for (String host : List.of("", "127.0.0.1:" + downstream.getAddress().getPort())) {
                var request = request("/unmatched", method).method(method, HttpRequest.BodyPublishers.noBody());
                if (!host.isEmpty()) request.header("X-Original-Host", host);
                var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(response.body()).isEqualTo(method.equals("HEAD") ? "" : "No mock rule found for SSE request.");
            }
        }
        assertThat(downstreamRequests.get()).isZero();
    }

    @Test
    void unmatchedSseGetAndHeadKeep404DespiteOriginalHost() throws Exception {
        for (String method : List.of("GET", "HEAD")) {
            for (String host : List.of("", "127.0.0.1:1")) {
                var request = request("/unmatched", method).method(method, HttpRequest.BodyPublishers.noBody());
                if (!host.isEmpty()) request.header("X-Original-Host", host);
                var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(response.body()).isEqualTo(method.equals("HEAD") ? "" : "No mock rule found for SSE request.");
            }
        }
        assertThat(downstreamRequests.get()).isZero();
    }

    @Test
    void ordinaryGetAndHeadWithoutSseAcceptStillUseDefaultConnection() throws Exception {
        configureDefaultConnection();
        int count = 0;
        for (String method : List.of("GET", "HEAD")) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/unmatched"))
                    .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(response.body()).isEqualTo(method.equals("HEAD") ? "" : "synthetic downstream: ");
            assertThat(received.get().method()).isEqualTo(method);
            assertThat(received.get().path()).isEqualTo("/base/unmatched");
            assertThat(downstreamRequests.get()).isEqualTo(++count);
        }
    }

    @Test
    void matchedSseStillStreamsInsteadOfUsingDefaultConnection() throws Exception {
        configureDefaultConnection();
        var rule = RuleDto.builder().protocol(Protocol.HTTP).matchKey("/matched").method("POST")
                .status(200).bodyCondition("$.prompt=synthetic 中文😀").sseEnabled(true)
                .responseBody("[{\"data\":\"{{{request.body}}}\"}]").build();
        assertThat(adminClient().postForEntity("/api/admin/rules", rule, RuleDto.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        var response = client.send(request("/matched", "POST").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertThat(reader.readLine()).isEqualTo("data:" + BODY);
            assertThat(reader.readLine()).isEmpty();
            assertThat(reader.readLine()).isNull();
        }
        assertThat(downstreamRequests.get()).isZero();
    }

    @Test
    void unmatchedSseAcceptWithBodyUsesOriginalHostWhenNoDefaultExists() throws Exception {
        HttpsServer originalHost = HttpsServer.create(loopbackAddress(), 0);
        try {
            originalHost.setHttpsConfigurator(new HttpsConfigurator(syntheticTlsContext()));
            registerDownstream(originalHost);
            originalHost.start();
            int count = 0;
            for (String method : METHODS) {
                var response = client.send(request("/unmatched?source=synthetic", method)
                        .header("X-Original-Host", "127.0.0.1:" + originalHost.getAddress().getPort()).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertForwarded(response, method, "/unmatched?source=synthetic", ++count);
            }
        } finally {
            originalHost.stop(0);
        }
    }

    private SSLContext syntheticTlsContext() throws Exception {
        Path keyStorePath = tempDirectory.resolve("synthetic-loopback.p12");
        String password = "synthetic-test-only";
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "loopback", "-keyalg", "RSA", "-keystore", keyStorePath.toString(),
                "-storetype", "PKCS12", "-storepass", password, "-keypass", password,
                "-dname", "CN=localhost", "-validity", "1", "-noprompt").redirectErrorStream(true).start();
        try {
            assertThat(keytool.waitFor(10, TimeUnit.SECONDS)).as("synthetic certificate generation finished").isTrue();
            assertThat(keytool.exitValue()).isZero();
        } finally {
            keytool.destroyForcibly();
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) { store.load(input, password.toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    private record ReceivedRequest(String method, String path, String accept, String body, String originalHost) { }
}
