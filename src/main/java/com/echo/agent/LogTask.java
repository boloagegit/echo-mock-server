package com.echo.agent;

import com.echo.entity.Protocol;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Log Agent 的任務值物件。
 * <p>
 * 包含所有日誌欄位（與 {@code RequestLogService.LogEntry} 相同）
 * 以及匹配分析上下文（candidates、analysisBody、queryString、headers）。
 * 解析後的 XML DOM / JSON tree 絕不可進入此物件；背景分析需要時會從
 * {@code analysisBody} 重新建立短生命週期的 PreparedBody。
 * <p>
 * {@code candidates} 與 {@code headers} 為不可變集合，
 * 透過靜態工廠方法以 {@code List.copyOf()} 與 {@code Map.copyOf()} 確保不可變性。
 */
@Getter
@Builder
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
    private final boolean forwarded;
    private final String forwardTarget;
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
    private final String analysisBody;
    private final String queryString;
    private final Map<String, String> headers;
    private final Map<String, Boolean> matchOutcomes;

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
            List<CandidateSnapshot> candidates,
            String analysisBody,
            String queryString,
            Map<String, String> headers,
            Map<String, Boolean> matchOutcomes) {
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
        this.candidates = candidates != null ? List.copyOf(candidates) : List.of();
        this.analysisBody = analysisBody;
        this.queryString = queryString;
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
        this.matchOutcomes = matchOutcomes != null ? Map.copyOf(matchOutcomes) : Map.of();
    }
}
