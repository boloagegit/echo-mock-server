package com.echo.integration.http;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.service.ConditionMatcher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pause after the first extraction, publish an admin edit, then finish the original request. */
class XPathRuleUpdateInFlightIntegrationTest extends BaseIntegrationTest {
    static final String PATH = "/xpath-inflight";
    static final String TYPE = "//Order/Type[contains(.,'VI')]";
    static final String OTHER = "//Order/Other[contains(.,'REG')]";
    static final ObjectMapper JSON = new ObjectMapper();
    @MockitoSpyBean ConditionMatcher matcher;
    @Autowired MeterRegistry meters;
    @Autowired ObjectMapper applicationJson;
    final AtomicBoolean armed = new AtomicBoolean();
    CountDownLatch entered, release;
    ExecutorService caller;

    @BeforeEach
    void gateAfterFirstExtraction() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        caller = Executors.newSingleThreadExecutor();
        doAnswer(
                        call -> {
                            boolean result = (Boolean) call.callRealMethod();
                            if (armed.compareAndSet(true, false)) {
                                assertThat(result)
                                        .as(
                                                "the first different-value rule must fail after"
                                                        + " warming the request cache")
                                        .isFalse();
                                entered.countDown();
                                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                            }
                            return result;
                        })
                .when(matcher)
                .matchesPrepared(
                        any(ConditionMatcher.RuleConditions.class),
                        any(ConditionMatcher.PreparedBody.class),
                        nullable(String.class),
                        any());
    }

    @AfterEach
    void releaseAndClose() throws InterruptedException {
        armed.set(false);
        release.countDown();
        caller.shutdownNow();
        assertThat(caller.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    JsonNode spec(String xpath, String value) {
        return JSON.valueToTree(
                Map.of(
                        "source",
                        "body",
                        "selector",
                        Map.of("xpath", xpath),
                        "op",
                        "equals",
                        "value",
                        value));
    }

    RuleDto create(String value, String reply, int priority) {
        var dto =
                RuleDto.builder()
                        .protocol(Protocol.HTTP)
                        .method("POST")
                        .matchKey(PATH)
                        .matchSpec(spec(TYPE, value))
                        .responseBody(reply)
                        .status(200)
                        .priority(priority)
                        .build();
        var result = adminClient().postForEntity("/api/admin/rules", dto, RuleDto.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().getMatchSpec())
                .as("must exercise structured rules")
                .isNotNull();
        return result.getBody();
    }

    RuleDto seed() {
        create("VI-NEVER", "never", 20);
        var main = create("VIP", "old", 10);
        assertThat(post("VIP", "REG").getBody()).isEqualTo("old");
        return main;
    }

    ResponseEntity<String> post(String type, String other) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return restTemplate.exchange(
                "/mock" + PATH,
                HttpMethod.POST,
                new HttpEntity<>(
                        "<Order><Type>" + type + "</Type><Other>" + other + "</Other></Order>",
                        headers),
                String.class);
    }

    void update(RuleDto created, String xpath, String expected, boolean rewriteResponse) {
        var current =
                adminClient().getForObject("/api/admin/rules/" + created.getId(), RuleDto.class);
        Long previousResponseId = current.getResponseId();
        current.setMatchSpec(spec(xpath, expected));
        current.setResponseBody("new");
        if (rewriteResponse) {
            current.setUpdateExistingResponse(true);
        } else {
            current.setResponseId(null);
            current.setUpdateExistingResponse(false);
        }
        // RuleDto marks this flag WRITE_ONLY, so a Java client serializing the DTO would omit it.
        Map<String, Object> payload =
                applicationJson.convertValue(current, new TypeReference<>() {});
        payload.put("updateExistingResponse", rewriteResponse);
        var saved =
                adminClient()
                        .exchange(
                                "/api/admin/rules/" + created.getId(),
                                HttpMethod.PUT,
                                new HttpEntity<>(payload),
                                RuleDto.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        if (rewriteResponse) {
            assertThat(saved.getBody().getResponseId()).isEqualTo(previousResponseId);
        } else {
            assertThat(saved.getBody().getResponseId()).isNotEqualTo(previousResponseId);
        }
    }

    Future<ResponseEntity<String>> pausedRequest() throws Exception {
        armed.set(true);
        var future = caller.submit(() -> post("VIP", "REG"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        return future;
    }

    @Test
    void changedExpectedValueKeepsInflightSnapshotAndNextRequestUsesNewRule() throws Exception {
        var main = seed();
        var inflight = pausedRequest();
        update(main, TYPE, "VI-REG", false);
        assertThat(inflight.isDone()).isFalse();
        release.countDown();
        assertThat(inflight.get(10, TimeUnit.SECONDS).getBody()).isEqualTo("old");
        assertThat(post("VIP", "REG").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("VI-REG", "REG").getBody()).isEqualTo("new");
    }

    @Test
    void changedXPathDoesNotReplaceInflightConditionsOrLeakValuesToNextRequest() throws Exception {
        var main = seed();
        var inflight = pausedRequest();
        update(main, OTHER, "REG", false);
        release.countDown();
        assertThat(inflight.get(10, TimeUnit.SECONDS).getBody()).isEqualTo("old");
        assertThat(post("REG", "REG").getBody()).isEqualTo("new");
        assertThat(post("VIP", "NO").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rewrittenSharedResponseTriggersFreshSelectionInsteadOfMixingOldRuleAndNewBody()
            throws Exception {
        var main = seed();
        var inflight = pausedRequest();
        double before = meters.counter("echo.response.consistency.fallbacks").count();
        update(main, TYPE, "VI-REG", true);
        release.countDown();
        var completed = inflight.get(10, TimeUnit.SECONDS);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(meters.counter("echo.response.consistency.fallbacks").count())
                .isEqualTo(before + 1);
        assertThat(post("VI-REG", "REG").getBody()).isEqualTo("new");
    }
}
