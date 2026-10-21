package com.echo.integration.http;

import com.echo.integration.base.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Live-port checks: prove native handling by default and exercise boundaries MVC-only tests cannot cover. */
@TestPropertySource(properties = "echo.http.request-timeout-ms=2000")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class NativeHttpTransportIntegrationTest extends BaseIntegrationTest {
    @Autowired MeterRegistry meters;
    @LocalServerPort int port;

    private double nativeRequests() {
        var counter = meters.find("echo.http.native.requests").tag("mode", "native").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void nativeResponseRetainsUnicodeStatusHeadersAndMetrics() {
        createHttpRule("/native", "GET", "{\"名字\":\"測試\"}", null, 201,
                null, "id=7", "X-Token=secret", "{\"X-Result\":\"yes\"}", null, null, null);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Token", "secret");
        double before = nativeRequests();
        var response = restTemplate.exchange("/mock/native?id=7", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo("{\"名字\":\"測試\"}");
        assertThat(response.getHeaders().getFirst("X-Result")).isEqualTo("yes");
        assertThat(nativeRequests()).isEqualTo(before + 1);
        // Receiving the body can precede the server's exchange-completion observation.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var timer = meters.find("http.server.requests").tag("uri", "/mock/**").tag("status", "201").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        });
    }

    @Test
    void declaredRequestCharsetIsDecodedBeforeMatching() {
        createHttpRule("/charset", "POST", "matched", null, 200,
                "name=測試", null, null, null, null, null, null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_16LE));
        double before = nativeRequests();
        var response = restTemplate.postForEntity("/mock/charset",
                new HttpEntity<>("{\"name\":\"測試\"}".getBytes(StandardCharsets.UTF_16LE), headers), String.class);
        assertThat(response.getBody()).isEqualTo("matched");
        assertThat(nativeRequests()).isEqualTo(before + 1);
    }

    @Test
    void adminUpdatesAndDeletionImmediatelyAffectNativeRequests() {
        var rule = createHttpRule("/mutable", "GET", "before");
        assertThat(restTemplate.getForObject("/mock/mutable", String.class)).isEqualTo("before");
        rule.setResponseBody("after");
        var updated = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rule), String.class);
        assertThat(updated.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(restTemplate.getForObject("/mock/mutable", String.class)).isEqualTo("after");
        var deleted = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.DELETE, null, String.class);
        assertThat(deleted.getStatusCode().value()).as(deleted.getBody()).isEqualTo(204);
        var absent = restTemplate.getForEntity("/mock/mutable", String.class);
        assertThat(absent.getStatusCode().value()).as(absent.getBody()).isEqualTo(404);
    }

    @Test
    void delayAndTimeoutAreHonoured() {
        createHttpRule("/delayed", "GET", "delayed", null, 200, null, null, null, null, 50L, null, null);
        long start = System.nanoTime();
        var delayed = restTemplate.getForEntity("/mock/delayed", String.class);
        assertThat(delayed.getStatusCode().value()).isEqualTo(200);
        assertThat(delayed.getBody()).isEqualTo("delayed");
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(45);
        createHttpRule("/timeout", "GET", "too late", null, 200, null, null, null, null, 10000L, null, null);
        long timeoutStart = System.nanoTime();
        var timeout = restTemplate.getForEntity("/mock/timeout", String.class);
        assertThat(timeout.getStatusCode().value()).isEqualTo(504);
        assertThat(timeout.getBody()).isEqualTo("Request timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - timeoutStart)).isLessThan(Duration.ofSeconds(5));
        assertThat(restTemplate.getForObject("/mock/delayed", String.class)).isEqualTo("delayed");
    }

    @Test
    void headAndExplicitSseRetainServletHandling() {
        createHttpRule("/fallback", null, "ordinary");
        double before = nativeRequests();
        var head = restTemplate.exchange("/mock/fallback", HttpMethod.HEAD, null, String.class);
        assertThat(head.getStatusCode().value()).isEqualTo(200);
        assertThat(head.getBody()).isNull();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));
        var sse = restTemplate.exchange("/mock/fallback", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(sse.getBody()).isEqualTo("ordinary");
        assertThat(nativeRequests()).isEqualTo(before);
    }

    @Test
    void expectContinueRetainsTheServletHandshake() throws Exception {
        createHttpRule("/continue", "POST", "accepted");
        double before = nativeRequests();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/continue"))
                .version(HttpClient.Version.HTTP_1_1).expectContinue(true).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"body\":\"value\"}")).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("accepted");
        assertThat(nativeRequests()).isEqualTo(before);
    }

    @Test
    void headerWhitespaceMatchesServletBehavior() throws Exception {
        createHttpRule("/header-firewall", "GET", "must not bypass firewall");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock/header-firewall"))
                .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(5))
                .header("X-Test", "one\ttwo").build();
        var client = HttpClient.newHttpClient();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // Undertow may normalize legal header whitespace before either adapter sees it.
        // Compare with forced Servlet handling instead of inventing a stricter HTTP contract.
        var servletRequest = HttpRequest.newBuilder(request, (name, value) -> true)
                .header("Forwarded", "for=127.0.0.1").build();
        var servletResponse = client.send(servletRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(servletResponse.statusCode());
        assertThat(response.body()).isEqualTo(servletResponse.body());
    }

    @Test
    void nativePrefixCannotBypassAdminAuthenticationOrFirewall() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        double before = nativeRequests();
        for (String path : new String[]{"/api/admin/rules/all", "/mock/../api/admin/rules/all",
                "/mock/%2e%2e/api/admin/rules/all", "/mocking/../api/admin/rules/all"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isIn(400, 401, 403, 404);
        }
        assertThat(nativeRequests()).isEqualTo(before);
    }
}
