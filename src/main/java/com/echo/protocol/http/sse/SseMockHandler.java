package com.echo.protocol.http.sse;

import com.echo.diagnostics.TransactionDiagnostics.Trace;
import com.echo.entity.FaultType;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.pipeline.AbstractMockPipeline;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.service.ConditionMatcher;
import com.echo.service.HttpRuleService;
import com.echo.service.MatchDescriptionBuilder;
import com.echo.service.MatchResult;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.RuleService;
import com.echo.service.ServeEvent;
import com.echo.service.ServeEventDispatcher;
import com.echo.service.ServeEventListener;
import com.echo.service.SseEventSequence;
import com.echo.service.SseRuleSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP request for {@code text/event-stream} explicitly: play the event sequence as {@link SsePlayer} when the SSE rule is hit,
 * In other cases, return the general HTTP pipeline.
 * <p>
 * Deliberately different from the general pipeline (maintaining the existing behavior): GET/HEAD that does not hit will directly return 404 and will not be forwarded;
 * When the SSE rule encounters HEAD/OPTIONS, it returns 405 and does not write the request record; the delay is not applied for connection reset failures.
 */
@Component
@Slf4j
public class SseMockHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RuleService ruleService;
    private final HttpRuleService httpRuleService;
    private final ResponseTemplateService templateService;
    private final HttpMockPipeline httpMockPipeline;
    private final SsePlayer player;
    private final RequestLogService requestLogService;
    private ServeEventDispatcher serveEvents;

    @Autowired
    public SseMockHandler(RuleService ruleService, HttpRuleService httpRuleService,
                          RequestLogService requestLogService, ResponseTemplateService templateService,
                          HttpMockPipeline httpMockPipeline, SsePlayer player) {
        this.ruleService = ruleService;
        this.httpRuleService = httpRuleService;
        this.templateService = templateService;
        this.httpMockPipeline = httpMockPipeline;
        this.player = player;
        this.requestLogService = requestLogService;
        this.serveEvents = ServeEventDispatcher.of(requestLogService, List.of());
    }

    @Autowired(required = false)
    public void setServeEventListeners(List<ServeEventListener> listeners) {
        this.serveEvents = ServeEventDispatcher.of(requestLogService, listeners);
    }

    public SsePlayer player() {
        return player;
    }

    /**
     * Process a SSE request and return {@link SseEmitter} or general {@link ResponseEntity}.
     *
     * @param connectionReset Close the underlying connection (connection reset failure)
     * @param httpPipeline    Change to normal HTTP pipeline
     */
    // Playback of completed future will only complete normally (the error has been handled within the player and written diagnostics), there is no need to wait here.
    @SuppressWarnings("FutureReturnValueIgnored")
    public Object handle(MockRequest request, HttpServletResponse response,
                         Runnable connectionReset, Supplier<Object> httpPipeline) {
        long startTime = System.currentTimeMillis();
        Trace trace = request.getTrace();
        String method = request.getMethod();
        String path = request.getPath();
        String body = request.getBody();
        trace.event("RECEIVED", "method", method, "path", path);

        log.debug("SSE request: host={}, path={}, method={}", request.getTargetHost(), path, method);

        MatchResult<HttpRule> matchResult;
        Integer candidateCount = null;
        if (httpMockPipeline != null) {
            List<HttpRule> candidates = httpRuleService.findPreparedHttpRules(request.getTargetHost(), path, method);
            candidateCount = candidates.size();
            matchResult = httpMockPipeline.matchRule(candidates,
                    body == null ? ConditionMatcher.PreparedBody.rawOnly(null)
                            : httpMockPipeline.prepareBodyForMatching(MockRequest.builder().body(body).build(), candidates),
                    request.getQueryString(), request.getHeaders());
        } else {
            // Compatibility path reserved for legacy unit tests and embedded callers.
            matchResult = httpRuleService.findMatchingHttpRuleWithCandidates(
                    request.getTargetHost(), path, method, body, request.getQueryString(), request.getHeaders());
        }
        long matchTime = System.currentTimeMillis() - startTime;
        String matchChainJson = MatchDescriptionBuilder.toMatchChainJson(matchResult.getMatchChain(), matchResult.isMatched());
        String scenarioSkipped = MatchDescriptionBuilder.toScenarioSkippedJson(matchResult.getMatchChain());

        if (!matchResult.isMatched()) {
            if (httpMockPipeline != null && !"GET".equals(method) && !"HEAD".equals(method)) {
                trace.end("DELEGATED_HTTP_PIPELINE");
                return httpPipeline.get();
            }
            long responseTime = System.currentTimeMillis() - startTime;
            serveEvents.dispatch(ServeEvent.builder()
                    .protocol(Protocol.HTTP).method(method).endpoint(path).matched(false)
                    .responseTimeMs((int) responseTime).clientIp(request.getClientIp())
                    .matchChain(matchChainJson).responseStatus(404).matchTimeMs((int) matchTime)
                    .candidateCount(candidateCount).scenarioSkipped(scenarioSkipped)
                    .requestBody(body).diagnosticId(trace.id()).build());
            trace.end("UNMATCHED");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("No mock rule found for SSE request.");
        }

        HttpRule rule = matchResult.getMatchedRule();
        trace.event("ROUTE", "ruleId", rule.getId(), "ruleVersion", rule.getVersion(),
                "responseId", rule.getResponseId(), "sse", rule.getSseEnabled(), "fault", rule.getFaultType());
        int configuredStatus = rule.getHttpStatus() == null ? 200 : rule.getHttpStatus();
        if (Boolean.TRUE.equals(rule.getSseEnabled())
                && ("HEAD".equals(method) || "OPTIONS".equals(method))) {
            trace.end("METHOD_NOT_ALLOWED");
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .header(HttpHeaders.ALLOW, "GET, POST, PUT, PATCH, DELETE")
                    .contentType(MediaType.TEXT_PLAIN).body("HTTP method does not support SSE playback");
        }
        if (httpMockPipeline != null && (!Boolean.TRUE.equals(rule.getSseEnabled()) || configuredStatus != 200)) {
            trace.end("DELEGATED_HTTP_PIPELINE");
            return httpPipeline.get();
        }
        AbstractMockPipeline.ScenarioTransition scenarioTransition = httpMockPipeline != null
                ? httpMockPipeline.advanceScenarioState(rule)
                : null;
        Served served = new Served(request, rule, startTime, matchTime, matchChainJson, scenarioTransition,
                candidateCount, scenarioSkipped, matchResult.isByFallback());

        FaultType faultType = rule.getFaultType() != null ? rule.getFaultType() : FaultType.NONE;
        if (faultType == FaultType.CONNECTION_RESET) {
            served.record(faultType, null, null);
            connectionReset.run();
            trace.end("FAULT_CONNECTION_RESET");
            return ResponseEntity.ok().build();
        }
        if (faultType == FaultType.EMPTY_RESPONSE) {
            served.record(faultType, configuredStatus, "");
            response.setStatus(configuredStatus);
            try {
                response.getOutputStream().flush();
            } catch (Exception e) {
                trace.failure("SSE_EMPTY_FLUSH_FAILED", e);
                log.warn("SSE empty response flush failed: {}", e.getMessage());
            }
            trace.end("FAULT_EMPTY_RESPONSE");
            return ResponseEntity.status(configuredStatus).body("");
        }

        // Non-SSE rule (only compatible paths will go here) → General response
        if (!Boolean.TRUE.equals(rule.getSseEnabled())) {
            return plainResponse(served, rule, configuredStatus);
        }

        // responseId is null → HTTP 500
        if (rule.getResponseId() == null) {
            served.record(faultType, 500, null);
            trace.end("MISSING_RESPONSE");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("SSE rule has no response body configured (responseId is null).");
        }

        String responseBody = ruleService.findResponseBodyById(rule.getResponseId()).orElse("");
        List<SsePlayer.SseEvent> events = SsePlayer.parseEvents(responseBody);

        // The event list is empty → HTTP 500
        if (events.isEmpty()) {
            served.record(faultType, 500, null);
            trace.end("INVALID_EVENTS");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("SSE rule has no valid events.");
        }

        boolean loopEnabled = Boolean.TRUE.equals(rule.getSseLoopEnabled());
        long initialDelay;
        HttpHeaders headers;
        long timeout;
        try {
            initialDelay = AbstractMockPipeline.calculateDelay(rule.getDelayMs() == null ? 0 : rule.getDelayMs(), rule.getMaxDelayMs());
            headers = SseRuleSettings.headers(rule.getHttpHeaders(), configuredStatus);
            timeout = SseRuleSettings.timeout(SseEventSequence.parse(responseBody), initialDelay, loopEnabled);
        } catch (IllegalArgumentException error) {
            served.record(faultType, 500, null);
            trace.failure("SSE_SETTINGS_INVALID", error);
            trace.end("INVALID_SETTINGS");
            return ResponseEntity.internalServerError().contentType(MediaType.TEXT_PLAIN).body(error.getMessage());
        }
        served.record(faultType, configuredStatus, responseBody,
                configuredStatus == 200 ? events.size() : null, configuredStatus == 200 ? initialDelay : null);
        if (configuredStatus != 200) {
            trace.end("NON_SSE_RESULT_READY_NOT_DELIVERY_CONFIRMATION");
            return ResponseEntity.status(configuredStatus).headers(headers).body(responseBody);
        }
        if (response != null) headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        SseEmitter emitter = new SseEmitter(timeout);
        trace.event("SSE_START", "eventCount", events.size(), "loop", loopEnabled, "timeoutMs", timeout);

        player.play(emitter, events, loopEnabled,
                new SsePlayer.RequestContext(request.getQueryString(), request.getHeaders(), path, method, body),
                initialDelay, trace, connectionReset);
        return emitter;
    }

    private Object plainResponse(Served served, HttpRule rule, int status) {
        MockRequest request = served.request;
        String responseBody = rule.getResponseId() != null
                ? ruleService.findResponseBodyById(rule.getResponseId()).orElse("")
                : "";

        responseBody = ResponseTemplateService.renderIfTemplate(templateService, responseBody, request::templateContext);

        served.record(FaultType.NONE, status, responseBody);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(detectContentType(responseBody));
        if (rule.getHttpHeaders() != null && !rule.getHttpHeaders().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> customHeaders = OBJECT_MAPPER.readValue(rule.getHttpHeaders(), Map.class);
                customHeaders.forEach(headers::add);
            } catch (Exception e) {
                log.warn("Failed to parse httpHeaders: {}", e.getMessage());
            }
        }
        request.getTrace().end("NON_SSE_RESULT_READY_NOT_DELIVERY_CONFIRMATION");
        return ResponseEntity.status(status).headers(headers).body(responseBody);
    }

    private static MediaType detectContentType(String body) {
        if (body == null) {
            return MediaType.TEXT_PLAIN;
        }
        String t = body.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            return MediaType.APPLICATION_JSON;
        }
        if (t.startsWith("<")) {
            return MediaType.APPLICATION_XML;
        }
        return MediaType.TEXT_PLAIN;
    }

    /** Write the shared information required for the request record after hitting the rule. */
    private final class Served {
        private final MockRequest request;
        private final HttpRule rule;
        private final long startTime;
        private final long matchTime;
        private final String matchChainJson;
        private final AbstractMockPipeline.ScenarioTransition scenarioTransition;
        private final Integer candidateCount;
        private final String scenarioSkipped;
        private final boolean byFallback;

        private Served(MockRequest request, HttpRule rule, long startTime, long matchTime,
                       String matchChainJson, AbstractMockPipeline.ScenarioTransition scenarioTransition,
                       Integer candidateCount, String scenarioSkipped, boolean byFallback) {
            this.request = request;
            this.rule = rule;
            this.startTime = startTime;
            this.matchTime = matchTime;
            this.matchChainJson = matchChainJson;
            this.scenarioTransition = scenarioTransition;
            this.candidateCount = candidateCount;
            this.scenarioSkipped = scenarioSkipped;
            this.byFallback = byFallback;
        }

        /** The rules required for matching analysis, query and headers (the same as the existing record content) are only attached when there is a fault or Scenario transfer. */
        void record(FaultType faultType, Integer responseStatus, String responseBody) {
            record(faultType, responseStatus, responseBody, null, null);
        }

        /**
         * @param sseEventCount The number of SSE events that started playing; null when there is no playback
         * @param delayMs       Delay before first event; null when not playing
         */
        void record(FaultType faultType, Integer responseStatus, String responseBody,
                    Integer sseEventCount, Long delayMs) {
            boolean annotated = faultType != FaultType.NONE || scenarioTransition != null;
            serveEvents.dispatch(ServeEvent.builder()
                    .ruleId(rule.getId()).protocol(Protocol.HTTP)
                    .method(request.getMethod()).endpoint(request.getPath()).matched(true)
                    .responseTimeMs((int) (System.currentTimeMillis() - startTime))
                    .clientIp(request.getClientIp()).matchChain(matchChainJson)
                    .targetHost(request.getTargetHost())
                    .responseStatus(responseStatus).matchTimeMs((int) matchTime)
                    .requestBody(request.getBody()).responseBody(responseBody)
                    .candidates(annotated ? List.of(rule) : null)
                    .preparedBody(annotated ? ConditionMatcher.PreparedBody.rawOnly(request.getBody()) : null)
                    .queryString(annotated ? request.getQueryString() : null)
                    .headers(annotated ? request.getHeaders() : null)
                    .faultType(faultType != FaultType.NONE ? faultType.name() : null)
                    .scenarioName(scenarioTransition != null ? scenarioTransition.name() : null)
                    .scenarioFromState(scenarioTransition != null ? scenarioTransition.fromState() : null)
                    .scenarioToState(scenarioTransition != null ? scenarioTransition.toState() : null)
                    .candidateCount(candidateCount).scenarioSkipped(scenarioSkipped)
                    .matchedByFallback(byFallback)
                    .sseEventCount(sseEventCount)
                    .delayMs(delayMs != null ? (int) Math.min(delayMs, Integer.MAX_VALUE) : null)
                    .diagnosticId(request.getTrace().id())
                    .build());
        }
    }
}
