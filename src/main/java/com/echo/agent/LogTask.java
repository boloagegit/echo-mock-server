package com.echo.agent;

import com.echo.entity.Protocol;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The task value object of Log Agent.
 * <p>
 * Contains all log fields (same as {@code RequestLogService.LogEntry})
 * and matching analysis context (candidates, analysisBody, queryString, headers).
 * Never retain a parsed XML DOM or JSON tree in this object; background analysis creates a
 * short-lived PreparedBody from {@code analysisBody} instead.
 * <p>
 * {@code candidates} and {@code headers} are immutable collections.
 * Ensuring immutability via static factory methods with {@code List.copyOf()} and {@code Map.copyOf()}.
 */
@Getter
@Builder
public class LogTask {

    // --- Basic log fields ---
    private final String ruleId;
    private final Protocol protocol;
    private final String method;
    private final String endpoint;
    private final boolean matched;
    private final int responseTimeMs;
    private final Integer matchTimeMs;
    private final String clientIp;
    private final LocalDateTime requestTime;
    private final String matchChain;
    private final String targetHost;
    private final boolean forwarded;
    private final String forwardTarget;
    private final Integer proxyStatus;
    private final String proxyError;
    private final Integer responseStatus;
    private final String requestBody;
    private final String responseBody;

    private final String faultType;

    // Scenario state-transition information
    private final String scenarioName;
    private final String scenarioFromState;
    private final String scenarioToState;

    // --- Processing information ---
    private final Integer candidateCount;
    private final Boolean matchedByFallback;
    private final Integer delayMs;
    private final Integer sseEventCount;
    private final String scenarioSkipped;

    // --- Match analysis context ---
    private final List<CandidateSnapshot> candidates;
    private final String analysisBody;
    private final String queryString;
    private final Map<String, String> headers;
    private final Map<String, Boolean> matchOutcomes;
    private final String diagnosticId;

    private LogTask(
            String ruleId,
            Protocol protocol,
            String method,
            String endpoint,
            boolean matched,
            int responseTimeMs,
            Integer matchTimeMs,
            String clientIp,
            LocalDateTime requestTime,
            String matchChain,
            String targetHost,
            boolean forwarded,
            String forwardTarget,
            Integer proxyStatus,
            String proxyError,
            Integer responseStatus,
            String requestBody,
            String responseBody,
            String faultType,
            String scenarioName,
            String scenarioFromState,
            String scenarioToState,
            Integer candidateCount,
            Boolean matchedByFallback,
            Integer delayMs,
            Integer sseEventCount,
            String scenarioSkipped,
            List<CandidateSnapshot> candidates,
            String analysisBody,
            String queryString,
            Map<String, String> headers,
            Map<String, Boolean> matchOutcomes,
            String diagnosticId) {
        this.diagnosticId = diagnosticId;
        this.ruleId = ruleId;
        this.protocol = protocol;
        this.method = method;
        this.endpoint = endpoint;
        this.matched = matched;
        this.responseTimeMs = responseTimeMs;
        this.matchTimeMs = matchTimeMs;
        this.clientIp = clientIp;
        this.requestTime = requestTime;
        this.matchChain = matchChain;
        this.targetHost = targetHost;
        this.forwarded = forwarded;
        this.forwardTarget = forwardTarget;
        this.proxyStatus = proxyStatus;
        this.proxyError = proxyError;
        this.responseStatus = responseStatus;
        this.requestBody = requestBody;
        this.responseBody = responseBody;
        this.faultType = faultType;
        this.scenarioName = scenarioName;
        this.scenarioFromState = scenarioFromState;
        this.scenarioToState = scenarioToState;
        this.candidateCount = candidateCount;
        this.matchedByFallback = matchedByFallback;
        this.delayMs = delayMs;
        this.sseEventCount = sseEventCount;
        this.scenarioSkipped = scenarioSkipped;
        this.candidates = candidates != null ? List.copyOf(candidates) : List.of();
        this.analysisBody = analysisBody;
        this.queryString = queryString;
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
        this.matchOutcomes = matchOutcomes != null ? Map.copyOf(matchOutcomes) : Map.of();
    }
}
