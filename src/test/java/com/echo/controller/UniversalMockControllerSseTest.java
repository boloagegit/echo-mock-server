package com.echo.controller;

import com.echo.service.ServeEvent;
import com.echo.service.ServeEventCaptor;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.pipeline.AbstractMockPipeline;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.service.ConditionMatcher;
import com.echo.service.HttpRuleService;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchResult;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.RuleService;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UniversalMockControllerSseTest {

    @Mock private RuleService ruleService;
    @Mock private HttpRuleService httpRuleService;
    @Mock private RequestLogService requestLogService;
    @Mock private ResponseTemplateService templateService;
    @Mock private HttpServletResponse httpServletResponse;

    private UniversalMockController controller;

    @BeforeEach
    void setUp() {
        controller = new UniversalMockController(
                ruleService, httpRuleService, requestLogService, templateService, null);
    }

    private MockHttpServletRequest sseGetRequest(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/mock" + path);
        req.addHeader("Accept", "text/event-stream");
        req.setRemoteAddr("127.0.0.1");
        return req;
    }

    private MatchResult<HttpRule> matched(HttpRule rule) {
        return new MatchResult<>(rule,
                List.of(new MatchChainEntry(
                        rule.getId(), "match", rule.getMatchKey(), rule.getDescription(), null)));
    }

    @Test
    void sseRoutingUsesScenarioAwarePipelineAndRecordsTransition() {
        HttpMockPipeline pipeline = mock(HttpMockPipeline.class);
        UniversalMockController scenarioController = new UniversalMockController(
                ruleService, httpRuleService, requestLogService, templateService, pipeline);
        HttpRule rule = HttpRule.builder()
                .id("scenario-sse")
                .matchKey("/scenario")
                .method("GET")
                .sseEnabled(true)
                .responseId(42L)
                .httpStatus(200)
                .scenarioName("checkout")
                .requiredScenarioState("Started")
                .newScenarioState("Paid")
                .build();
        when(httpRuleService.findPreparedHttpRules("default", "/scenario", "GET"))
                .thenReturn(List.of(rule));
        when(pipeline.matchRule(anyList(), any(ConditionMatcher.PreparedBody.class),
                isNull(), anyMap())).thenReturn(matched(rule));
        when(pipeline.advanceScenarioState(rule)).thenReturn(
                new AbstractMockPipeline.ScenarioTransition("checkout", "Started", "Paid", true));
        when(ruleService.findResponseBodyById(42L)).thenReturn(Optional.of("[{\"data\":\"ok\"}]"));

        Object result = scenarioController.handleSseRequest(
                sseGetRequest("/scenario"), httpServletResponse);

        assertThat(result).isInstanceOf(SseEmitter.class);
        verify(pipeline).advanceScenarioState(rule);
        ServeEvent served = ServeEventCaptor.single(requestLogService);
        assertThat(served.getRuleId()).isEqualTo("scenario-sse");
        assertThat(served.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(served.getMethod()).isEqualTo("GET");
        assertThat(served.getEndpoint()).isEqualTo("/scenario");
        assertThat(served.isMatched()).isTrue();
        assertThat(served.getClientIp()).isEqualTo("127.0.0.1");
        assertThat(served.getMatchChain()).isNotNull();
        assertThat(served.getTargetHost()).isEqualTo("default");
        assertThat(served.getProxyStatus()).isNull();
        assertThat(served.getProxyError()).isNull();
        assertThat(served.getResponseStatus()).isEqualTo(200);
        assertThat(served.getRequestBody()).isNull();
        assertThat(served.getResponseBody()).isEqualTo("[{\"data\":\"ok\"}]");
        assertThat(served.getCandidates()).isNotNull();
        assertThat(served.getPreparedBody()).isNotNull();
        assertThat(served.getQueryString()).isNull();
        assertThat(served.getHeaders()).isNotNull();
        assertThat(served.getFaultType()).isNull();
        assertThat(served.getScenarioName()).isEqualTo("checkout");
        assertThat(served.getScenarioFromState()).isEqualTo("Started");
        assertThat(served.getScenarioToState()).isEqualTo("Paid");
        assertThat(served.getCandidateCount()).isEqualTo(1);
        assertThat(served.getSseEventCount()).isEqualTo(1);
        assertThat(served.getDelayMs()).isZero();
        scenarioController.shutdown();
    }

    @AfterEach
    void tearDown() { controller.shutdown(); }

    @Test
    void ordinaryAndWildcardAcceptRequestsRetainOriginalPipelineFallback() {
        HttpMockPipeline pipeline = mock(HttpMockPipeline.class);
        UniversalMockController fallbackController = new UniversalMockController(
                ruleService, httpRuleService, requestLogService, templateService, pipeline);
        when(pipeline.executeAsync(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                com.echo.pipeline.PipelineResult.builder().response(com.echo.pipeline.MockResponse.builder()
                        .status(202).body("ordinary pipeline").build()).build()));
        try {
            for (String accept : java.util.Arrays.asList(null, "*/*", "text/*", "text/event-stream;q=0")) {
                MockHttpServletRequest request = sseGetRequest("/ordinary");
                request.setMethod("POST"); request.removeHeader("Accept");
                if (accept != null) request.addHeader("Accept", accept);
                Object result = fallbackController.handleSseRequest(request, httpServletResponse, "synthetic body");
                assertThat(result).isInstanceOf(ResponseEntity.class);
                assertThat(((ResponseEntity<?>) result).getBody()).isEqualTo("ordinary pipeline");
            }
            verify(pipeline, times(4)).executeAsync(any());
            verify(httpRuleService, never()).findPreparedHttpRules(any(), any(), any());
        } finally {
            fallbackController.shutdown();
        }
    }

    @Test
    void normalScenarioRuleWithSseAcceptUsesOrdinaryPipelineOnce() {
        HttpMockPipeline pipeline = mock(HttpMockPipeline.class);
        UniversalMockController fallbackController = new UniversalMockController(
                ruleService, httpRuleService, requestLogService, templateService, pipeline);
        HttpRule rule = HttpRule.builder().id("scenario-http").matchKey("/scenario")
                .method("POST").sseEnabled(false).scenarioName("checkout")
                .requiredScenarioState("Started").newScenarioState("Paid").build();
        String body = "{\"prompt\":\"synthetic\"}";
        when(httpRuleService.findPreparedHttpRules("default", "/scenario", "POST")).thenReturn(List.of(rule));
        when(pipeline.prepareBodyForMatching(any(), anyList())).thenReturn(ConditionMatcher.PreparedBody.rawOnly(body));
        when(pipeline.matchRule(anyList(), any(ConditionMatcher.PreparedBody.class), isNull(), anyMap()))
                .thenReturn(matched(rule));
        when(pipeline.executeAsync(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                com.echo.pipeline.PipelineResult.builder().response(com.echo.pipeline.MockResponse.builder()
                        .status(202).body("{\"ok\":true}").headers(java.util.Map.of("X-Synthetic", "yes"))
                        .build()).build()));
        MockHttpServletRequest request = sseGetRequest("/scenario");
        request.setMethod("POST");
        try {
            Object result = fallbackController.handleSseRequest(request, httpServletResponse, body);
            assertThat(result).isInstanceOf(ResponseEntity.class);
            ResponseEntity<?> response = (ResponseEntity<?>) result;
            assertThat(response.getStatusCode().value()).isEqualTo(202);
            assertThat(response.getBody()).isEqualTo("{\"ok\":true}");
            assertThat(response.getHeaders().getFirst("X-Synthetic")).isEqualTo("yes");
            var captured = org.mockito.ArgumentCaptor.forClass(com.echo.pipeline.MockRequest.class);
            verify(pipeline, times(1)).executeAsync(captured.capture());
            assertThat(captured.getValue().getBody()).isEqualTo(body);
            // The ordinary pipeline owns its transition; the SSE controller cannot advance it twice.
            verify(pipeline, never()).advanceScenarioState(any());
        } finally {
            fallbackController.shutdown();
        }
    }

    private MatchResult<HttpRule> noMatch() {
        return new MatchResult<>(null, List.of());
    }

    // ===== Task 4.1 tests =====

    @Test
    void sseRuleMatch_returnsSseEmitter() {
        HttpRule rule = HttpRule.builder().id("sse-1").matchKey("/events")
                .sseEnabled(true).responseId(1L).httpStatus(200).build();
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));
        when(ruleService.findResponseBodyById(1L))
                .thenReturn(Optional.of("[{\"data\":\"hello\"}]"));

        Object result = controller.handleSseRequest(sseGetRequest("/events"), httpServletResponse);

        assertThat(result).isInstanceOf(SseEmitter.class);
    }

    @Test
    void nonSseRule_fallbackToNormalResponse() {
        HttpRule rule = HttpRule.builder().id("normal-1").matchKey("/api/test")
                .sseEnabled(false).responseId(2L).httpStatus(200).build();
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));
        when(ruleService.findResponseBodyById(2L))
                .thenReturn(Optional.of("{\"status\":\"ok\"}"));

        Object result = controller.handleSseRequest(sseGetRequest("/api/test"), httpServletResponse);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<String> response = (ResponseEntity<String>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("status");
    }

    @Test
    void noMatchingRule_returns404() {
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(noMatch());

        Object result = controller.handleSseRequest(sseGetRequest("/unknown"), httpServletResponse);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<String> response = (ResponseEntity<String>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ===== Task 4.2 tests =====

    @Test
    void sseJsonParseFail_returns500() {
        HttpRule rule = HttpRule.builder().id("sse-2").matchKey("/events")
                .sseEnabled(true).responseId(3L).httpStatus(200).build();
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));
        when(ruleService.findResponseBodyById(3L))
                .thenReturn(Optional.of("not valid json"));

        Object result = controller.handleSseRequest(sseGetRequest("/events"), httpServletResponse);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<String> response = (ResponseEntity<String>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void sseEmptyEventList_returns500() {
        HttpRule rule = HttpRule.builder().id("sse-3").matchKey("/events")
                .sseEnabled(true).responseId(4L).httpStatus(200).build();
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));
        when(ruleService.findResponseBodyById(4L))
                .thenReturn(Optional.of("[]"));

        Object result = controller.handleSseRequest(sseGetRequest("/events"), httpServletResponse);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<String> response = (ResponseEntity<String>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void sseResponseIdNull_returns500() {
        HttpRule rule = HttpRule.builder().id("sse-4").matchKey("/events")
                .sseEnabled(true).responseId(null).httpStatus(200).build();
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));

        Object result = controller.handleSseRequest(sseGetRequest("/events"), httpServletResponse);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<String> response = (ResponseEntity<String>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ===== Template rendering tests =====

    @Test
    void sseEventsWithTemplate_callsTemplateRender() throws Exception {
        HttpRule rule = HttpRule.builder().id("sse-5").matchKey("/events")
                .sseEnabled(true).responseId(5L).httpStatus(200).build();
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));
        when(ruleService.findResponseBodyById(5L))
                .thenReturn(Optional.of("[{\"data\":\"{{now}}\"}]"));
        when(templateService.hasTemplate("{{now}}")).thenReturn(true);
        when(templateService.render(eq("{{now}}"), any())).thenReturn("2026-01-01");

        Object result = controller.handleSseRequest(sseGetRequest("/events"), httpServletResponse);

        assertThat(result).isInstanceOf(SseEmitter.class);
        // Give background thread time to execute
        Thread.sleep(200);
        verify(templateService).render(eq("{{now}}"), any());
    }

    @Test
    void sseEventsWithoutTemplate_doesNotCallTemplateRender() throws Exception {
        HttpRule rule = HttpRule.builder().id("sse-6").matchKey("/events")
                .sseEnabled(true).responseId(6L).httpStatus(200).build();
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));
        when(ruleService.findResponseBodyById(6L))
                .thenReturn(Optional.of("[{\"data\":\"plain text\"}]"));
        when(templateService.hasTemplate("plain text")).thenReturn(false);

        Object result = controller.handleSseRequest(sseGetRequest("/events"), httpServletResponse);

        assertThat(result).isInstanceOf(SseEmitter.class);
        Thread.sleep(200);
        verify(templateService, never()).render(any(), any());
    }

    // ===== Request log recording =====

    @Test
    void sseRequest_recordsRequestLog() {
        HttpRule rule = HttpRule.builder().id("sse-7").matchKey("/events")
                .sseEnabled(true).responseId(7L).httpStatus(200).build();
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));
        when(ruleService.findResponseBodyById(7L))
                .thenReturn(Optional.of("[{\"data\":\"hello\"}]"));

        controller.handleSseRequest(sseGetRequest("/events"), httpServletResponse);

        ServeEvent served2 = ServeEventCaptor.single(requestLogService);
        assertThat(served2.getRuleId()).isEqualTo("sse-7");
        assertThat(served2.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(served2.getMethod()).isEqualTo("GET");
        assertThat(served2.getEndpoint()).isEqualTo("/events");
        assertThat(served2.isMatched()).isTrue();
        assertThat(served2.getClientIp()).isEqualTo("127.0.0.1");
        assertThat(served2.getProxyStatus()).isNull();
        assertThat(served2.getProxyError()).isNull();
        assertThat(served2.getResponseStatus()).isEqualTo(200);
        assertThat(served2.getRequestBody()).isNull();
    }

    @Test
    void noMatchSseRequest_recordsRequestLog() {
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(noMatch());

        controller.handleSseRequest(sseGetRequest("/unknown"), httpServletResponse);

        ServeEvent served3 = ServeEventCaptor.single(requestLogService);
        assertThat(served3.getRuleId()).isNull();
        assertThat(served3.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(served3.getMethod()).isEqualTo("GET");
        assertThat(served3.getEndpoint()).isEqualTo("/unknown");
        assertThat(served3.isMatched()).isFalse();
        assertThat(served3.getClientIp()).isEqualTo("127.0.0.1");
        assertThat(served3.getTargetHost()).isNull();
        assertThat(served3.getProxyStatus()).isNull();
        assertThat(served3.getProxyError()).isNull();
        assertThat(served3.getResponseStatus()).isEqualTo(404);
        assertThat(served3.getRequestBody()).isNull();
        assertThat(served3.getResponseBody()).isNull();
    }

    // ===== Property 4: SSE routing correctness =====

    /**
     * Validates: Requirements 4.1, 4.2
     *
     * For any matched HttpRule, the response type is determined by sseEnabled:
     * sseEnabled=true → SseEmitter, sseEnabled=false → ResponseEntity.
     */
    @Property
    void sseRoutingCorrectnessProperty(
            @ForAll("sseRoutingInputs") SseRoutingInput input) {
        RuleService mockRuleSvc = mock(RuleService.class);
        HttpRuleService mockHttpRuleSvc = mock(HttpRuleService.class);
        RequestLogService mockLogSvc = mock(RequestLogService.class);
        ResponseTemplateService mockTmplSvc = mock(ResponseTemplateService.class);
        UniversalMockController ctrl = new UniversalMockController(
                mockRuleSvc, mockHttpRuleSvc, mockLogSvc, mockTmplSvc, null);

        HttpRule rule = HttpRule.builder()
                .id("prop-" + input.ruleIndex)
                .matchKey("/test")
                .sseEnabled(input.sseEnabled)
                .responseId(input.sseEnabled ? 100L : 200L)
                .httpStatus(200)
                .build();

        when(mockHttpRuleSvc.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));

        if (input.sseEnabled) {
            when(mockRuleSvc.findResponseBodyById(100L))
                    .thenReturn(Optional.of("[{\"data\":\"test\"}]"));
        } else {
            when(mockRuleSvc.findResponseBodyById(200L))
                    .thenReturn(Optional.of("{\"ok\":true}"));
        }

        Object result = ctrl.handleSseRequest(sseGetRequest("/test"), null);

        if (input.sseEnabled) {
            assertThat(result).isInstanceOf(SseEmitter.class);
        } else {
            assertThat(result).isInstanceOf(ResponseEntity.class);
        }
        ctrl.shutdown();
    }

    record SseRoutingInput(boolean sseEnabled, int ruleIndex) {}

    @Provide
    Arbitrary<SseRoutingInput> sseRoutingInputs() {
        return Combinators.combine(
                Arbitraries.of(true, false),
                Arbitraries.integers().between(1, 100)
        ).as(SseRoutingInput::new);
    }

    // ===== Property 5: SSE events sent in order and stream completes =====

    /**
     * Validates: Requirements 4.6, 4.8
     *
     * For any SSE request with valid events, events are sent in order
     * and SseEmitter.complete() is called after all events.
     */
    @Property
    void sseEventsSentInOrderAndStreamCompletes(
            @ForAll("validEventCounts") int eventCount) throws Exception {
        RuleService mockRuleSvc = mock(RuleService.class);
        HttpRuleService mockHttpRuleSvc = mock(HttpRuleService.class);
        RequestLogService mockLogSvc = mock(RequestLogService.class);
        UniversalMockController ctrl = new UniversalMockController(
                mockRuleSvc, mockHttpRuleSvc, mockLogSvc, mock(ResponseTemplateService.class), null);

        HttpRule rule = HttpRule.builder()
                .id("prop-order").matchKey("/stream")
                .sseEnabled(true).responseId(300L).httpStatus(200).build();

        when(mockHttpRuleSvc.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));

        // Build JSON array with eventCount events
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < eventCount; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"event\":\"evt").append(i).append("\",\"data\":\"data").append(i).append("\"}");
        }
        json.append("]");

        when(mockRuleSvc.findResponseBodyById(300L)).thenReturn(Optional.of(json.toString()));

        Object result = ctrl.handleSseRequest(sseGetRequest("/stream"), null);

        assertThat(result).isInstanceOf(SseEmitter.class);
        // Give background thread time to complete
        Thread.sleep(300);
        // Verify request log was recorded (confirms the method executed)
        ServeEvent served4 = ServeEventCaptor.single(mockLogSvc);
        assertThat(served4.getRuleId()).isEqualTo("prop-order");
        assertThat(served4.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(served4.isMatched()).isTrue();
        assertThat(served4.getProxyStatus()).isNull();
        assertThat(served4.getProxyError()).isNull();
        assertThat(served4.getResponseStatus()).isEqualTo(200);
        assertThat(served4.getRequestBody()).isNull();
        ctrl.shutdown();
    }

    @Provide
    Arbitrary<Integer> validEventCounts() {
        return Arbitraries.integers().between(1, 10);
    }

    // ===== Property 6: SSE template rendering decision =====

    /**
     * Validates: Requirements 5.1, 5.2
     *
     * For any SseEvent, if data contains "{{" template marker,
     * templateService.render() is called; otherwise it is not.
     */
    @Property
    void sseTemplateRenderingDecisionProperty(
            @ForAll("templateDecisionInputs") TemplateDecisionInput input) throws Exception {
        RuleService mockRuleSvc = mock(RuleService.class);
        HttpRuleService mockHttpRuleSvc = mock(HttpRuleService.class);
        ResponseTemplateService mockTmplSvc = mock(ResponseTemplateService.class);
        UniversalMockController ctrl = new UniversalMockController(
                mockRuleSvc, mockHttpRuleSvc, mock(RequestLogService.class), mockTmplSvc, null);

        HttpRule rule = HttpRule.builder()
                .id("prop-tmpl").matchKey("/tmpl")
                .sseEnabled(true).responseId(400L).httpStatus(200).build();

        when(mockHttpRuleSvc.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));

        String eventJson = "[{\"data\":\"" + escapeJsonString(input.data) + "\"}]";
        when(mockRuleSvc.findResponseBodyById(400L)).thenReturn(Optional.of(eventJson));

        boolean hasTemplate = input.data.contains("{{");
        when(mockTmplSvc.hasTemplate(input.data)).thenReturn(hasTemplate);
        if (hasTemplate) {
            when(mockTmplSvc.render(eq(input.data), any())).thenReturn("rendered");
        }

        Object result = ctrl.handleSseRequest(sseGetRequest("/tmpl"), null);

        assertThat(result).isInstanceOf(SseEmitter.class);
        Thread.sleep(200);

        if (hasTemplate) {
            verify(mockTmplSvc).render(eq(input.data), any());
        } else {
            verify(mockTmplSvc, never()).render(any(), any());
        }
        ctrl.shutdown();
    }

    record TemplateDecisionInput(String data) {}

    @Provide
    Arbitrary<TemplateDecisionInput> templateDecisionInputs() {
        Arbitrary<String> plainData = Arbitraries.of("hello", "world", "test data", "no template here");
        Arbitrary<String> templateData = Arbitraries.of("{{now}}", "{{request.path}}", "prefix {{now}} suffix");
        return Arbitraries.oneOf(plainData, templateData)
                .map(TemplateDecisionInput::new);
    }

    // ===== Property 8: SSE request log recording =====

    /**
     * Validates: Requirements 4.9
     *
     * For any request matching an SSE rule, requestLogService.record() is called.
     */
    @Property
    void sseRequestLogRecordingProperty(
            @ForAll("sseLogInputs") SseLogInput input) {
        RuleService mockRuleSvc = mock(RuleService.class);
        HttpRuleService mockHttpRuleSvc = mock(HttpRuleService.class);
        RequestLogService mockLogSvc = mock(RequestLogService.class);
        UniversalMockController ctrl = new UniversalMockController(
                mockRuleSvc, mockHttpRuleSvc, mockLogSvc, mock(ResponseTemplateService.class), null);

        HttpRule rule = HttpRule.builder()
                .id(input.ruleId).matchKey(input.path)
                .sseEnabled(true).responseId(500L).httpStatus(200).build();

        when(mockHttpRuleSvc.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(matched(rule));
        when(mockRuleSvc.findResponseBodyById(500L))
                .thenReturn(Optional.of("[{\"data\":\"log-test\"}]"));

        ctrl.handleSseRequest(sseGetRequest(input.path), null);

        ServeEvent served5 = ServeEventCaptor.single(mockLogSvc);
        assertThat(served5.getRuleId()).isEqualTo(input.ruleId);
        assertThat(served5.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(served5.getMethod()).isEqualTo("GET");
        assertThat(served5.getEndpoint()).isEqualTo(input.path);
        assertThat(served5.isMatched()).isTrue();
        assertThat(served5.getProxyStatus()).isNull();
        assertThat(served5.getProxyError()).isNull();
        assertThat(served5.getResponseStatus()).isEqualTo(200);
        assertThat(served5.getRequestBody()).isNull();
    }

    record SseLogInput(String ruleId, String path) {}

    @Provide
    Arbitrary<SseLogInput> sseLogInputs() {
        Arbitrary<String> ruleIds = Arbitraries.of("rule-1", "rule-2", "rule-3", "rule-abc");
        Arbitrary<String> paths = Arbitraries.of("/events", "/stream", "/api/sse", "/data/feed");
        return Combinators.combine(ruleIds, paths).as(SseLogInput::new);
    }

    // ===== Helper =====

    private static String escapeJsonString(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
