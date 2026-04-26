package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchDescriptionBuilder;
import com.echo.service.RequestLogService;
import com.echo.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Log Agent — 非同步批次寫入請求日誌。
 * <p>
 * 支援兩種儲存模式：
 * <ul>
 *   <li>Database 模式：批次 saveAll + 超過 max-records 時 deleteOldest</li>
 *   <li>Memory 模式：寫入 RequestLogService 的 memory ring buffer</li>
 * </ul>
 * <p>
 * 當 {@code echo.agent.analysis.enabled=true} 時，在 processBatch 中對每個 LogTask
 * 執行匹配鏈分析（placeholder，將在 Task 7.2 實作）。
 */
@Component
@Slf4j
public class LogAgent extends AbstractBatchAgent<LogTask> {

    private static final String AGENT_NAME = "log-agent";

    private final RequestLogRepository requestLogRepository;
    private final SystemConfigService configService;
    private final ConditionMatcher conditionMatcher;
    private final RequestLogService requestLogService;

    private final boolean analysisEnabled;

    public LogAgent(
            RequestLogRepository requestLogRepository,
            SystemConfigService configService,
            ConditionMatcher conditionMatcher,
            RequestLogService requestLogService,
            @Value("${echo.agent.log.queue-capacity:500}") int queueCapacity,
            @Value("${echo.agent.log.batch-size:50}") int batchSize,
            @Value("${echo.agent.log.flush-interval-seconds:5}") int flushIntervalSeconds,
            @Value("${echo.agent.analysis.enabled:true}") boolean analysisEnabled) {
        super(queueCapacity, batchSize, flushIntervalSeconds);
        this.requestLogRepository = requestLogRepository;
        this.configService = configService;
        this.conditionMatcher = conditionMatcher;
        this.requestLogService = requestLogService;
        this.analysisEnabled = analysisEnabled;
    }

    @PostConstruct
    public void init() {
        start();
    }

    @PreDestroy
    public void destroy() {
        shutdown();
    }

    @Override
    public String getName() {
        return AGENT_NAME;
    }

    @Override
    public String getDescription() {
        return "Async batch log writer with match chain analysis";
    }

    @Override
    protected void processBatch(List<LogTask> batch) {
        if (configService.isRequestLogMemoryMode()) {
            processMemoryMode(batch);
        } else {
            processDatabaseMode(batch);
        }
    }

    @Override
    protected LogTask castTask(Object task) {
        if (task instanceof LogTask logTask) {
            return logTask;
        }
        return null;
    }

    /**
     * Database 模式：convert LogTask to RequestLog entity, saveAll + deleteOldest。
     */
    private void processDatabaseMode(List<LogTask> batch) {
        try {
            List<RequestLog> entities = batch.stream()
                    .map(this::toEntity)
                    .toList();
            requestLogRepository.saveAll(entities);

            long count = requestLogRepository.count();
            int maxRecords = configService.getRequestLogMaxRecords();
            if (count > maxRecords) {
                requestLogRepository.deleteOldest((int) (count - maxRecords));
            }
        } catch (Exception e) {
            log.warn("LogAgent failed to persist logs: {}", e.getMessage());
        }
    }

    /**
     * Memory 模式：寫入 RequestLogService 的 memory ring buffer。
     */
    private void processMemoryMode(List<LogTask> batch) {
        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = requestLogService.getMemoryBuffer();
        AtomicInteger bufferSize = requestLogService.getBufferSize();
        if (buffer == null) {
            log.warn("LogAgent memory buffer not available");
            return;
        }

        int maxRecords = configService.getRequestLogMaxRecords();
        for (LogTask task : batch) {
            String matchChain = analyzeMatchChain(task);
            RequestLogService.LogEntry entry = toLogEntry(task, matchChain);
            buffer.addFirst(entry);
            int size = bufferSize.incrementAndGet();
            while (size > maxRecords) {
                if (buffer.pollLast() != null) {
                    size = bufferSize.decrementAndGet();
                } else {
                    break;
                }
            }
        }
    }

    /**
     * 匹配鏈分析。
     * <p>
     * 當 {@code echo.agent.analysis.enabled=true} 時，遍歷 LogTask 的候選規則，
     * 對每個 enabled 規則呼叫 {@code conditionMatcher.matchesPreparedWithDetail()} 取得結構化結果，
     * 判斷 match / shadowed / near-miss / mismatch / skipped / fallback，
     * 序列化為 JSON 回傳。
     *
     * @param task LogTask
     * @return 分析後的 matchChain 字串
     */
    String analyzeMatchChain(LogTask task) {
        if (!analysisEnabled) {
            return task.getMatchChain();
        }

        List<CandidateSnapshot> candidates = task.getCandidates();
        if (candidates == null || candidates.isEmpty() || task.getPreparedBody() == null) {
            return task.getMatchChain();
        }

        try {
            List<MatchChainEntry> chain = new ArrayList<>();
            String matchedRuleId = task.getRuleId();
            boolean hasNearMiss = false;
            boolean hasShadowed = false;

            for (CandidateSnapshot candidate : candidates) {
                if (!candidate.isEnabled()) {
                    chain.add(new MatchChainEntry(
                            candidate.getRuleId(), "skipped",
                            candidate.getEndpoint(), candidate.getDescription(),
                            buildConditionString(candidate),
                            null, "Rule disabled", false));
                    continue;
                }

                boolean hasConditions = hasAnyCondition(candidate);

                if (!hasConditions) {
                    // Fallback rule (no conditions)
                    boolean isMatchedRule = candidate.getRuleId().equals(matchedRuleId);
                    String reason = isMatchedRule ? "match" : "fallback";
                    chain.add(new MatchChainEntry(
                            candidate.getRuleId(), reason,
                            candidate.getEndpoint(), candidate.getDescription(),
                            null, null, null, false));
                    continue;
                }

                // Evaluate conditions
                ConditionMatcher.ConditionDetail detail = conditionMatcher.matchesPreparedWithDetail(
                        candidate.getBodyCondition(), candidate.getQueryCondition(),
                        candidate.getHeaderCondition(), task.getPreparedBody(),
                        task.getQueryString(), task.getHeaders());

                String detailStr = joinDetails(detail.getResults());

                boolean isMatchedRule = candidate.getRuleId().equals(matchedRuleId);

                if (detail.isOverallMatch()) {
                    if (isMatchedRule) {
                        chain.add(new MatchChainEntry(
                                candidate.getRuleId(), "match",
                                candidate.getEndpoint(), candidate.getDescription(),
                                buildConditionString(candidate),
                                detail.score(), detailStr, false));
                    } else if (!hasShadowed) {
                        hasShadowed = true;
                        chain.add(new MatchChainEntry(
                                candidate.getRuleId(), "shadowed",
                                candidate.getEndpoint(), candidate.getDescription(),
                                buildConditionString(candidate),
                                detail.score(), detailStr, false));
                    }
                } else if (detail.passedCount() > 0 && detail.passedCount() < detail.totalCount()) {
                    if (!hasNearMiss) {
                        hasNearMiss = true;
                        chain.add(new MatchChainEntry(
                                candidate.getRuleId(), "near-miss",
                                candidate.getEndpoint(), candidate.getDescription(),
                                buildConditionString(candidate),
                                detail.score(), detailStr, true));
                    }
                }
                // mismatch (all fail) — skip, not useful to show
            }

            return MatchDescriptionBuilder.toMatchChainJson(chain);
        } catch (Exception e) {
            log.warn("LogAgent match chain analysis failed: {}", e.getMessage());
            return task.getMatchChain();
        }
    }

    /**
     * 判斷候選規則是否有任何條件（body/query/header）。
     */
    private boolean hasAnyCondition(CandidateSnapshot candidate) {
        return isNotBlank(candidate.getBodyCondition())
                || isNotBlank(candidate.getQueryCondition())
                || isNotBlank(candidate.getHeaderCondition());
    }

    /**
     * 從 CandidateSnapshot 建構條件描述字串。
     */
    private String buildConditionString(CandidateSnapshot candidate) {
        StringBuilder sb = new StringBuilder();
        appendCondition(sb, "body", candidate.getBodyCondition());
        appendCondition(sb, "query", candidate.getQueryCondition());
        appendCondition(sb, "header", candidate.getHeaderCondition());
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static void appendCondition(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(label).append(": ").append(value);
        }
    }

    /**
     * 將 ConditionResult 列表的 detail 以 "; " 串接（避免 stream + Collectors.joining 的額外開銷）。
     */
    private static String joinDetails(List<ConditionMatcher.ConditionResult> results) {
        if (results.isEmpty()) {
            return "";
        }
        if (results.size() == 1) {
            return results.get(0).getDetail();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(results.get(i).getDetail());
        }
        return sb.toString();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 將 LogTask 轉換為 RequestLog entity（Database 模式用）。
     */
    private RequestLog toEntity(LogTask task) {
        String matchChain = analyzeMatchChain(task);
        return RequestLog.builder()
                .ruleId(task.getRuleId())
                .protocol(task.getProtocol())
                .method(task.getMethod())
                .endpoint(task.getEndpoint())
                .matched(task.isMatched())
                .responseTimeMs(task.getResponseTimeMs())
                .matchTimeMs(task.getMatchTimeMs())
                .clientIp(task.getClientIp())
                .requestTime(task.getRequestTime())
                .matchChain(matchChain)
                .targetHost(task.getTargetHost())
                .proxyStatus(task.getProxyStatus())
                .proxyError(task.getProxyError())
                .responseStatus(task.getResponseStatus())
                .requestBody(task.getRequestBody())
                .responseBody(task.getResponseBody())
                .faultType(task.getFaultType())
                .scenarioName(task.getScenarioName())
                .scenarioFromState(task.getScenarioFromState())
                .scenarioToState(task.getScenarioToState())
                .build();
    }

    /**
     * 將 LogTask 轉換為 RequestLogService.LogEntry（Memory 模式用）。
     */
    private RequestLogService.LogEntry toLogEntry(LogTask task, String matchChain) {
        return RequestLogService.LogEntry.builder()
                .id(RequestLogService.nextMemoryId())
                .ruleId(task.getRuleId())
                .protocol(task.getProtocol())
                .method(task.getMethod())
                .endpoint(task.getEndpoint())
                .matched(task.isMatched())
                .responseTimeMs(task.getResponseTimeMs())
                .matchTimeMs(task.getMatchTimeMs())
                .clientIp(task.getClientIp())
                .requestTime(task.getRequestTime())
                .matchChain(matchChain)
                .targetHost(task.getTargetHost())
                .proxyStatus(task.getProxyStatus())
                .proxyError(task.getProxyError())
                .responseStatus(task.getResponseStatus())
                .requestBody(task.getRequestBody())
                .responseBody(task.getResponseBody())
                .faultType(task.getFaultType())
                .scenarioName(task.getScenarioName())
                .scenarioFromState(task.getScenarioFromState())
                .scenarioToState(task.getScenarioToState())
                .build();
    }
}
