package com.echo.integration.http;

import com.echo.controller.UniversalMockController;
import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.ResponseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real Undertow/loopback HTTP reads prove arrival before the entire response completes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "echo.jms.enabled=false", "echo.jms.target.enabled=false",
        "echo.ldap.enabled=false", "echo.backup.enabled=false", "echo.cleanup.enabled=false"})
@Timeout(65)
class SsePlaybackRegressionIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private UniversalMockController controller;
    @Autowired private ResponseRepository responses;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private RuleDto rule(String path, String body) {
        return RuleDto.builder().protocol(Protocol.HTTP).matchKey(path).method("GET")
                .status(200).sseEnabled(true).responseBody(body).build();
    }
    private RuleDto save(RuleDto rule) {
        var response = adminClient().postForEntity("/api/admin/rules", rule, RuleDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock" + path))
                .header("Accept", "text/event-stream").timeout(Duration.ofSeconds(60));
    }
    private BufferedReader reader(HttpResponse<java.io.InputStream> response) {
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }
    private String event(BufferedReader reader) throws Exception {
        StringBuilder event = new StringBuilder();
        for (String line; (line = reader.readLine()) != null;) {
            if (line.isEmpty()) return event.toString();
            event.append(line).append('\n');
        }
        return event.isEmpty() ? null : event.toString();
    }

    @Test
    void eventsArriveIndividuallyAndUtf8MultilineDataIsIntact() throws Exception {
        save(rule("/arrival", "[{\"data\":\"\\n中文😀\\r\\n末行\\n\",\"event\":\"更新\",\"id\":\"一\"},"
                + "{\"data\":\"second\",\"delayMs\":1500}]"));
        long start = System.nanoTime();
        var response = client.send(request("/arrival").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = reader(response)) {
            String first = event(reader);
            long firstArrival = System.nanoTime();
            assertThat(first).contains("data:\ndata:中文😀\ndata:末行\ndata:\n", "event:更新", "id:一");
            assertThat(Duration.ofNanos(firstArrival - start).toMillis()).isLessThan(1200);
            assertThat(event(reader)).contains("data:second");
            assertThat(Duration.ofNanos(System.nanoTime() - firstArrival).toMillis()).isGreaterThanOrEqualTo(1200);
            assertThat(event(reader)).isNull();
        }
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream", "UTF-8");
    }

    @Test
    void legalTwentyPlusTwentySecondSequenceCompletesBeyondOldTimeout() throws Exception {
        save(rule("/long", "[{\"data\":\"first\",\"delayMs\":20000},{\"data\":\"second\",\"delayMs\":20000}]"));
        long start = System.nanoTime();
        var response = client.send(request("/long").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = reader(response)) {
            assertThat(event(reader)).contains("data:first");
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(19_500);
            assertThat(event(reader)).contains("data:second");
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(39_500);
            assertThat(event(reader)).isNull();
        }
    }

    @Test
    void postPutPatchAndDeleteMatchRequestBodyAndRenderItInSseTemplate() throws Exception {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            RuleDto rule = rule("/body-" + method, "[{\"data\":\"{{{request.body}}}\"}]");
            rule.setMethod(method); rule.setBodyCondition("$.prompt=中文😀"); save(rule);
            var request = request("/body-" + method).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString("{\"prompt\":\"中文😀\"}"));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var reader = reader(response)) { assertThat(event(reader)).contains("中文😀"); }
        }
    }

    @Test
    void ruleDelayRangeIsAppliedOnceBeforeFirstEventAndHeadersArePreserved() throws Exception {
        RuleDto rule = rule("/settings", "[{\"data\":\"first\",\"delayMs\":150},{\"data\":\"second\",\"delayMs\":300}]");
        rule.setDelayMs(350L); rule.setMaxDelayMs(500L);
        rule.setResponseHeaders("{\"X-Synthetic\":\"yes\",\"Cache-Control\":\"no-store\"}"); save(rule);
        long start = System.nanoTime();
        var response = client.send(request("/settings").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.headers().firstValue("x-synthetic")).hasValue("yes");
        assertThat(response.headers().firstValue("cache-control")).hasValue("no-store");
        try (var reader = reader(response)) {
            assertThat(event(reader)).contains("data:first");
            long first = System.nanoTime();
            assertThat(Duration.ofNanos(first - start).toMillis()).isBetween(490L, 1400L);
            assertThat(event(reader)).contains("data:second");
            assertThat(Duration.ofNanos(System.nanoTime() - first).toMillis()).isBetween(290L, 620L);
        }
    }

    @Test
    void non200StatusesAndHeadersUseNormalHttpResponseIncluding204() throws Exception {
        for (int status : List.of(204, 201, 429, 503)) {
            RuleDto rule = rule("/status-" + status, "[{\"data\":\"synthetic error\"}]");
            rule.setStatus(status); rule.setDelayMs(150L);
            rule.setResponseHeaders("{\"X-Synthetic\":\"error\",\"Retry-After\":\"1\"}"); save(rule);
            long start = System.nanoTime();
            var response = client.send(request("/status-" + status).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(status);
            assertThat(response.headers().firstValue("x-synthetic")).hasValue("error");
            assertThat(response.headers().firstValue("content-type").orElse("")).doesNotContain("text/event-stream");
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(140);
            if (status == 204) assertThat(response.body()).isEmpty();
            else assertThat(response.body()).contains("synthetic error");
        }
    }

    @Test
    void ordinaryPostWithoutAcceptUsesOriginalPipelineForMatchedAndUnmatchedRules() throws Exception {
        RuleDto rule = rule("/ordinary-accept", "[{\"data\":\"synthetic\"}]");
        rule.setMethod("POST"); save(rule);
        for (String path : List.of("/ordinary-accept", "/ordinary-no-match")) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mock" + path))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("synthetic body")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.headers().firstValue("content-type").orElse("")).doesNotContain("text/event-stream");
            if (path.equals("/ordinary-accept")) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo("[{\"data\":\"synthetic\"}]");
            } else {
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(response.body()).contains("No mock rule found for: POST /ordinary-no-match");
            }
        }
    }

    @Test
    void normalPostWithSseAcceptRetainsBodyMatchingStatusHeadersAndDelay() throws Exception {
        createHttpRule("/normal-post", "POST", "ordinary", null, 202, "$.prompt=test", null, null,
                "{\"X-Synthetic\":\"normal\"}", 150L, null, null);
        long start = System.nanoTime();
        var response = client.send(request("/normal-post").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"test\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202); assertThat(response.body()).isEqualTo("ordinary");
        assertThat(response.headers().firstValue("x-synthetic")).hasValue("normal");
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isGreaterThanOrEqualTo(140);
    }

    @Test
    void clientAbortCancelsLoopAndLeavesNoQueuedSseTask() throws Exception {
        RuleDto rule = rule("/loop", "[{\"data\":\"tick\",\"delayMs\":100}]");
        rule.setSseLoopEnabled(true); save(rule);
        var response = client.send(request("/loop").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = reader(response)) { assertThat(event(reader)).contains("data:tick"); }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(controller.getPendingSseTaskCount()).isZero());
    }

    @Test
    @Timeout(5)
    void terminalErrorAndAbortStopLoopAndDoNotScheduleFollowingEvents() throws Exception {
        for (String type : List.of("error", "abort")) {
            RuleDto rule = rule("/terminal-" + type, "[{\"data\":\"first\"},{\"type\":\"" + type
                    + "\",\"data\":\"terminal\",\"delayMs\":150},{\"data\":\"forbidden\",\"delayMs\":30000}]");
            rule.setSseLoopEnabled(true); save(rule);
            var response = client.send(request("/terminal-" + type).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = reader(response)) {
                assertThat(event(reader)).contains("data:first");
                if (type.equals("error")) assertThat(event(reader)).contains("event:error", "data:terminal");
                try {
                    String remainder = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                    assertThat(remainder).doesNotContain("forbidden", "data:");
                } catch (java.io.UncheckedIOException error) {
                    // Fault playback may close transport abruptly instead of returning a clean EOF.
                }
            }
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(controller.getPendingSseTaskCount()).isZero());
        }
    }

    @Test
    void lastEventIdDoesNotResumeAndEachRequestStartsAtFirstEvent() throws Exception {
        save(rule("/restart", "[{\"id\":\"1\",\"data\":\"first\"},{\"id\":\"2\",\"data\":\"second\"}]"));
        for (int attempt = 0; attempt < 2; attempt++) {
            var response = client.send(request("/restart").header("Last-Event-ID", "2").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = reader(response)) {
                assertThat(event(reader)).contains("data:first", "id:1");
                assertThat(event(reader)).contains("data:second", "id:2");
            }
        }
    }

    @Test
    void unsupportedMethodsAndSettingsFailAtSaveAndWildcardHeadGets405() throws Exception {
        for (String method : List.of("HEAD", "OPTIONS", "TRACE", "*", "")) {
            RuleDto rule = rule("/method", "[{\"data\":\"x\"}]"); rule.setMethod(method);
            assertThat(adminClient().postForEntity("/api/admin/rules", rule, Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (String header : List.of("{\"Content-Type\":\"application/json\"}", "{\"Content-Length\":\"1\"}")) {
            RuleDto rule = rule("/headers", "[{\"data\":\"x\"}]"); rule.setResponseHeaders(header);
            assertThat(adminClient().postForEntity("/api/admin/rules", rule, Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        RuleDto rule = rule("/wildcard", "[{\"data\":\"x\"}]"); rule.setMethod(null); save(rule);
        var response = client.send(request("/wildcard").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(controller.getPendingSseTaskCount()).isZero();
    }

    @Test
    void sharedSseResponseUpdatesRejectInvalidBodyAndRetainOriginalContent() {
        String valid = "[{\"data\":\"original\"}]";
        RuleDto saved = save(rule("/shared-update", valid));
        for (String body : List.of("", "   ", "[null]", "[{\"data\":\"x\",\"comment\":\"x\"}]")) {
            var response = com.echo.entity.Response.builder().body(body).build();
            var update = adminClient().exchange("/api/admin/responses/" + saved.getResponseId(),
                    org.springframework.http.HttpMethod.PUT, new org.springframework.http.HttpEntity<>(response), Map.class);
            assertThat(update.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(responses.findById(saved.getResponseId()).orElseThrow().getBody()).isEqualTo(valid);
        }
        var typed = com.echo.entity.Response.builder().contentType("SSE_EVENTS").body("null").build();
        assertThat(adminClient().postForEntity("/api/admin/responses", typed, Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidSequencesAndInvalidReusedResponsesAreRejectedBeforeCreatingRecords() {
        for (String body : List.of("null", "[null]", "[{\"data\":null}]", "[{\"data\":true}]",
                "[{\"data\":\"x\",\"retry\":100}]", "[{\"data\":\"x\",\"comment\":\"x\"}]",
                "[{\"data\":\"x\",\"event\":\"bad\\nevent\"}]", "[{\"data\":\"x\",\"id\":\"bad\\rid\"}]")) {
            long count = responses.count();
            assertThat(adminClient().postForEntity("/api/admin/rules", rule("/invalid", body), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(responses.count()).isEqualTo(count);
        }
        var response = createResponse("synthetic non-SSE", "plain text");
        RuleDto rule = rule("/reuse", null); rule.setResponseId(response.getId());
        assertThat(adminClient().postForEntity("/api/admin/rules", rule, Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
