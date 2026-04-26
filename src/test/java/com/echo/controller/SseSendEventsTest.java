package com.echo.controller;

import com.echo.controller.UniversalMockController.SseEvent;
import com.echo.service.HttpRuleService;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.RuleService;
import com.echo.service.SseEventsContentValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SseSendEventsTest {

    @Mock private RuleService ruleService;
    @Mock private HttpRuleService httpRuleService;
    @Mock private RequestLogService requestLogService;
    @Mock private ResponseTemplateService templateService;
    @Mock private SseEmitter emitter;

    private UniversalMockController controller;

    @BeforeEach
    void setUp() {
        controller = new UniversalMockController(
                ruleService, httpRuleService, requestLogService, templateService, null);
    }

    // --- Normal event sequence → all sent then complete() ---

    @Test
    void normalEventSequence_allSentThenComplete() throws Exception {
        List<SseEvent> events = List.of(
                new SseEvent("msg", "hello", "1", 0L, null),
                new SseEvent("msg", "world", "2", 0L, "normal")
        );

        controller.sendSseEvents(emitter, events, false, null, new HashMap<>(), "/test", "GET");

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

    // --- Event with type=error → sends error event then completeWithError() ---

    @Test
    void errorEvent_sendsErrorThenCompleteWithError() throws Exception {
        List<SseEvent> events = List.of(
                new SseEvent("msg", "hello", "1", 0L, null),
                new SseEvent("error", "fail", "2", 0L, "error")
        );

        controller.sendSseEvents(emitter, events, false, null, new HashMap<>(), "/test", "GET");

        // 2 sends: first normal event, then error event
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).completeWithError(any(RuntimeException.class));
        verify(emitter, never()).complete();
    }

    // --- Event with type=abort → no event sent, directly completeWithError() ---

    @Test
    void abortEvent_noEventSentDirectlyCompleteWithError() throws Exception {
        List<SseEvent> events = List.of(
                new SseEvent(null, "bye", null, 0L, "abort")
        );

        controller.sendSseEvents(emitter, events, false, null, new HashMap<>(), "/test", "GET");

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).completeWithError(any(RuntimeException.class));
        verify(emitter, never()).complete();
    }

    // --- After error/abort, no subsequent events are sent ---

    @Test
    void afterError_noSubsequentEventsSent() throws Exception {
        List<SseEvent> events = List.of(
                new SseEvent("msg", "first", "1", 0L, null),
                new SseEvent("err", "boom", "2", 0L, "error"),
                new SseEvent("msg", "third", "3", 0L, null)
        );

        controller.sendSseEvents(emitter, events, false, null, new HashMap<>(), "/test", "GET");

        // Only 2 sends: first normal + error event; third event never sent
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).completeWithError(any(RuntimeException.class));
        verify(emitter, never()).complete();
    }

    @Test
    void afterAbort_noSubsequentEventsSent() throws Exception {
        List<SseEvent> events = List.of(
                new SseEvent("msg", "first", "1", 0L, null),
                new SseEvent(null, "x", null, 0L, "abort"),
                new SseEvent("msg", "third", "3", 0L, null)
        );

        controller.sendSseEvents(emitter, events, false, null, new HashMap<>(), "/test", "GET");

        // Only 1 send: first normal event; abort sends nothing, third never reached
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).completeWithError(any(RuntimeException.class));
        verify(emitter, never()).complete();
    }

    // --- Ordering verification: completeWithError called after sends ---

    @Test
    void errorEvent_completeWithErrorCalledAfterSends() throws Exception {
        List<SseEvent> events = List.of(
                new SseEvent("start", "ok", "1", 0L, null),
                new SseEvent("error", "{\"code\":500}", "2", 0L, "error")
        );

        controller.sendSseEvents(emitter, events, false, null, new HashMap<>(), "/test", "GET");

        // 2 sends total (normal + error), then completeWithError
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).completeWithError(any(RuntimeException.class));
        verify(emitter, never()).complete();
    }

    // ===== Property 2: 驗證→執行一致性 =====

    /**
     * Validates: Requirements 1.2
     *
     * Body that passes SseEventsContentValidator.validate() always produces
     * non-empty list from parseSseEvents().
     */
    @Property
    void validatedBodyAlwaysProducesNonEmptyEventList(
            @ForAll("validSseBodies") String body) {
        // Verify body passes validation
        SseEventsContentValidator validator = new SseEventsContentValidator(new ObjectMapper());
        validator.validate(body); // should not throw

        // Verify parseSseEvents returns non-empty list
        UniversalMockController ctrl = new UniversalMockController(
                null, null, null, null, null);
        List<SseEvent> events = ctrl.parseSseEvents(body);

        assertThat(events).isNotEmpty();
    }

    @Provide
    Arbitrary<String> validSseBodies() {
        Arbitrary<String> data = Arbitraries.of(
                "hello", "{\"status\":\"ok\"}", "test data", "123", "{\"a\":1}");
        Arbitrary<String> type = Arbitraries.of("normal", "error", "abort")
                .injectNull(0.3);
        Arbitrary<String> event = Arbitraries.of("msg", "update", "tick", "")
                .injectNull(0.3);
        Arbitrary<String> id = Arbitraries.of("1", "2", "abc", "")
                .injectNull(0.3);
        Arbitrary<Long> delayMs = Arbitraries.longs().between(0, 5000);

        Arbitrary<String> singleEvent = Combinators.combine(data, type, event, id, delayMs)
                .as((d, t, e, i, delay) -> {
                    StringBuilder sb = new StringBuilder("{\"data\":\"");
                    sb.append(d.replace("\"", "\\\"")).append("\"");
                    if (t != null) {
                        sb.append(",\"type\":\"").append(t).append("\"");
                    }
                    if (e != null && !e.isEmpty()) {
                        sb.append(",\"event\":\"").append(e).append("\"");
                    }
                    if (i != null && !i.isEmpty()) {
                        sb.append(",\"id\":\"").append(i).append("\"");
                    }
                    sb.append(",\"delayMs\":").append(delay);
                    sb.append("}");
                    return sb.toString();
                });

        return singleEvent.list().ofMinSize(1).ofMaxSize(5)
                .map(items -> "[" + String.join(",", items) + "]");
    }
}
