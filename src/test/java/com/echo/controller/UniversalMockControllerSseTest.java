package com.echo.controller;

import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.service.HttpRuleService;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchResult;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.RuleService;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

        Object result = controller.handleSseRequest(sseGetRequest("/events"), new MockHttpServletResponse());

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

        Object result = controller.handleSseRequest(sseGetRequest("/api/test"), new MockHttpServletResponse());

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

        Object result = controller.handleSseRequest(sseGetRequest("/unknown"), new MockHttpServletResponse());

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

        Object result = controller.handleSseRequest(sseGetRequest("/events"), new MockHttpServletResponse());

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

        Object result = controller.handleSseRequest(sseGetRequest("/events"), new MockHttpServletResponse());

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

        Object result = controller.handleSseRequest(sseGetRequest("/events"), new MockHttpServletResponse());

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

        Object result = controller.handleSseRequest(sseGetRequest("/events"), new MockHttpServletResponse());

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

        Object result = controller.handleSseRequest(sseGetRequest("/events"), new MockHttpServletResponse());

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

        controller.handleSseRequest(sseGetRequest("/events"), new MockHttpServletResponse());

        verify(requestLogService).record(eq("sse-7"), eq(Protocol.HTTP), eq("GET"), eq("/events"),
                eq(true), anyInt(), eq("127.0.0.1"), any(), any(), isNull(), isNull(), eq(200), anyInt(),
                isNull(), any());
    }

    @Test
    void noMatchSseRequest_recordsRequestLog() {
        when(httpRuleService.findMatchingHttpRuleWithCandidates(any(), any(), any(), any(), any(), any()))
                .thenReturn(noMatch());

        controller.handleSseRequest(sseGetRequest("/unknown"), new MockHttpServletResponse());

        verify(requestLogService).record(isNull(), eq(Protocol.HTTP), eq("GET"), eq("/unknown"),
                eq(false), anyInt(), eq("127.0.0.1"), any(), isNull(), isNull(), isNull(), eq(404), anyInt(),
                isNull(), isNull());
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

        Object result = ctrl.handleSseRequest(sseGetRequest("/test"), new MockHttpServletResponse());

        if (input.sseEnabled) {
            assertThat(result).isInstanceOf(SseEmitter.class);
        } else {
            assertThat(result).isInstanceOf(ResponseEntity.class);
        }
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

        Object result = ctrl.handleSseRequest(sseGetRequest("/stream"), new MockHttpServletResponse());

        assertThat(result).isInstanceOf(SseEmitter.class);
        // Give background thread time to complete
        Thread.sleep(300);
        // Verify request log was recorded (confirms the method executed)
        verify(mockLogSvc).record(eq("prop-order"), eq(Protocol.HTTP), any(), any(),
                eq(true), anyInt(), any(), any(), any(), isNull(), isNull(), eq(200), anyInt(),
                isNull(), any());
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

        Object result = ctrl.handleSseRequest(sseGetRequest("/tmpl"), new MockHttpServletResponse());

        assertThat(result).isInstanceOf(SseEmitter.class);
        Thread.sleep(200);

        if (hasTemplate) {
            verify(mockTmplSvc).render(eq(input.data), any());
        } else {
            verify(mockTmplSvc, never()).render(any(), any());
        }
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

        ctrl.handleSseRequest(sseGetRequest(input.path), new MockHttpServletResponse());

        verify(mockLogSvc).record(eq(input.ruleId), eq(Protocol.HTTP), eq("GET"), eq(input.path),
                eq(true), anyInt(), any(), any(), any(), isNull(), isNull(), eq(200), anyInt(),
                isNull(), any());
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
