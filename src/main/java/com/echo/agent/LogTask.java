package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.service.ConditionMatcher;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Log Agent 的任務值物件。
 * <p>
 * 包含所有日誌欄位（與 {@code RequestLogService.LogEntry} 相同）
 * 以及匹配分析上下文（candidates、preparedBody、queryString、headers）。
 * <p>
 * {@code candidates} 與 {@code headers} 為不可變集合，
 * 透過靜態工廠方法以 {@code List.copyOf()} 與 {@code Map.copyOf()} 確保不可變性。
 */
@Getter
public class LogTask {

    // --- 基本日誌欄位 ---
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
    private final Integer proxyStatus;
    private final String proxyError;
    private final Integer responseStatus;
    private final String requestBody;
    private final String responseBody;
    private final String faultType;

    // --- Scenario 狀態轉移資訊 ---
    private final String scenarioName;
    private final String scenarioFromState;
    private final String scenarioToState;

    // --- 匹配分析上下文 ---
    private final List<CandidateSnapshot> candidates;
    private final ConditionMatcher.PreparedBody preparedBody;
    private final String queryString;
    private final Map<String, String> headers;

    @Builder
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
            Integer proxyStatus,
            String proxyError,
            Integer responseStatus,
            String requestBody,
            String responseBody,
            String faultType,
            String scenarioName,
            String scenarioFromState,
            String scenarioToState,
            List<CandidateSnapshot> candidates,
            ConditionMatcher.PreparedBody preparedBody,
            String queryString,
            Map<String, String> headers) {
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
        this.proxyStatus = proxyStatus;
        this.proxyError = proxyError;
        this.responseStatus = responseStatus;
        this.requestBody = requestBody;
        this.responseBody = responseBody;
        this.faultType = faultType;
        this.scenarioName = scenarioName;
        this.scenarioFromState = scenarioFromState;
        this.scenarioToState = scenarioToState;
        this.candidates = candidates != null ? List.copyOf(candidates) : List.of();
        this.preparedBody = preparedBody;
        this.queryString = queryString;
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
    }
}
