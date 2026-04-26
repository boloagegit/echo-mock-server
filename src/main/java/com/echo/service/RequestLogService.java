package com.echo.service;

import com.echo.agent.AgentStatus;
import com.echo.agent.CandidateSnapshot;
import com.echo.agent.LogAgent;
import com.echo.agent.LogTask;
import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.RequestLogRepository;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 請求記錄服務
 * 
 * 支援兩種儲存模式:
 * - memory: 純記憶體環形緩衝區
 * - database: 寫入 DB，定期清理超過 max-records 的舊資料
 *
 * 日誌寫入委派給 LogAgent 非同步處理；當 LogAgent 不可用時降級為同步寫入。
 */
@Service
@Slf4j
public class RequestLogService {

    private final RequestLogRepository requestLogRepository;
    private final SystemConfigService configService;
    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ObjectProvider<LogAgent> logAgentProvider;

    // ===== Memory 模式: 環形緩衝區 =====
    private ConcurrentLinkedDeque<LogEntry> memoryBuffer;
    private final AtomicInteger bufferSize = new AtomicInteger(0);
    private volatile int maxRecords;

    /** Memory 模式用的 ID 產生器 */
    private static final AtomicLong MEMORY_ID_SEQ = new AtomicLong(1);

    /** 取得下一個 Memory 模式 ID（供 LogAgent 使用） */
    public static long nextMemoryId() {
        return MEMORY_ID_SEQ.getAndIncrement();
    }

    /** 只 warn 一次 LogAgent 不可用 */
    private final AtomicBoolean fallbackWarned = new AtomicBoolean(false);

    public RequestLogService(RequestLogRepository requestLogRepository,
                             SystemConfigService configService,
                             ProtocolHandlerRegistry protocolHandlerRegistry,
                             ObjectProvider<LogAgent> logAgentProvider) {
        this.requestLogRepository = requestLogRepository;
        this.configService = configService;
        this.protocolHandlerRegistry = protocolHandlerRegistry;
        this.logAgentProvider = logAgentProvider;
    }

    @PostConstruct
    public void init() {
        this.maxRecords = configService.getRequestLogMaxRecords();
        if (configService.isRequestLogMemoryMode()) {
            this.memoryBuffer = new ConcurrentLinkedDeque<>();
            log.info("Request log service initialized (memory mode, max {} records)", maxRecords);
        } else {
            log.info("Request log service initialized (database mode, max {} records)", maxRecords);
        }
    }

    public long count() {
        if (configService.isRequestLogMemoryMode()) {
            return memoryBuffer.size();
        }
        return requestLogRepository.count();
    }

    @Transactional
    public long deleteAll() {
        if (configService.isRequestLogMemoryMode()) {
            int size = memoryBuffer.size();
            memoryBuffer.clear();
            bufferSize.set(0);
            return size;
        }
        long count = requestLogRepository.count();
        requestLogRepository.deleteAllInBatch();
        return count;
    }

    /**
     * 依 ID 查詢單筆請求記錄
     */
    public Optional<LogEntry> findById(long id) {
        if (configService.isRequestLogMemoryMode()) {
            return memoryBuffer.stream()
                    .filter(e -> e.getId() != null && e.getId() == id)
                    .findFirst();
        }
        return requestLogRepository.findById(id).map(this::toEntry);
    }

    /**
     * 記錄請求
     */
    public void record(String ruleId, Protocol protocol, String endpoint,
                       boolean matched, int responseTimeMs, String clientIp) {
        record(ruleId, protocol, null, endpoint, matched, responseTimeMs, clientIp, null, null, null, null, null, null, null, null);
    }

    public void record(String ruleId, Protocol protocol, String endpoint,
                       boolean matched, int responseTimeMs, String clientIp, String matchChain) {
        record(ruleId, protocol, null, endpoint, matched, responseTimeMs, clientIp, matchChain, null, null, null, null, null, null, null);
    }

    public void record(String ruleId, Protocol protocol, String endpoint,
                       boolean matched, int responseTimeMs, String clientIp,
                       String matchChain, String targetHost) {
        record(ruleId, protocol, null, endpoint, matched, responseTimeMs, clientIp, matchChain, targetHost, null, null, null, null, null, null);
    }

    public void record(String ruleId, Protocol protocol, String method, String endpoint,
                       boolean matched, int responseTimeMs, String clientIp,
                       String matchChain, String targetHost,
                       Integer proxyStatus, String proxyError,
                       Integer responseStatus, Integer matchTimeMs) {
        record(ruleId, protocol, method, endpoint, matched, responseTimeMs, clientIp,
                matchChain, targetHost, proxyStatus, proxyError, responseStatus, matchTimeMs, null, null);
    }

    public void record(String ruleId, Protocol protocol, String method, String endpoint,
                       boolean matched, int responseTimeMs, String clientIp,
                       String matchChain, String targetHost,
                       Integer proxyStatus, String proxyError,
                       Integer responseStatus, Integer matchTimeMs,
                       String requestBody, String responseBody) {
        record(ruleId, protocol, method, endpoint, matched, responseTimeMs, clientIp,
                matchChain, targetHost, proxyStatus, proxyError, responseStatus, matchTimeMs,
                requestBody, responseBody, null, null, null, null, null);
    }

    @SuppressWarnings("java:S107") // 參數數量多是因為需要傳遞完整的匹配上下文
    public <T extends BaseRule> void record(String ruleId, Protocol protocol, String method, String endpoint,
                       boolean matched, int responseTimeMs, String clientIp,
                       String matchChain, String targetHost,
                       Integer proxyStatus, String proxyError,
                       Integer responseStatus, Integer matchTimeMs,
                       String requestBody, String responseBody,
                       List<T> candidates,
                       ConditionMatcher.PreparedBody preparedBody,
                       String queryString,
                       Map<String, String> headers,
                       String faultType) {
        record(ruleId, protocol, method, endpoint, matched, responseTimeMs, clientIp,
                matchChain, targetHost, proxyStatus, proxyError, responseStatus, matchTimeMs,
                requestBody, responseBody, candidates, preparedBody, queryString, headers, faultType,
                null, null, null);
    }

    /**
     * 記錄請求（含匹配上下文與 Scenario 狀態轉移資訊）
     * <p>
     * 當 LogAgent 可用且狀態為 RUNNING 時，建構 LogTask 並委派給 LogAgent 非同步處理。
     * 當 LogAgent 不可用或非 RUNNING 時，降級為同步寫入（memory buffer 或 database）。
     */
    @SuppressWarnings("java:S107") // 參數數量多是因為需要傳遞完整的匹配上下文
    public <T extends BaseRule> void record(String ruleId, Protocol protocol, String method, String endpoint,
                       boolean matched, int responseTimeMs, String clientIp,
                       String matchChain, String targetHost,
                       Integer proxyStatus, String proxyError,
                       Integer responseStatus, Integer matchTimeMs,
                       String requestBody, String responseBody,
                       List<T> candidates,
                       ConditionMatcher.PreparedBody preparedBody,
                       String queryString,
                       Map<String, String> headers,
                       String faultType,
                       String scenarioName,
                       String scenarioFromState,
                       String scenarioToState) {
        // 深拷貝候選規則為不可變快照（thread-safe，供 LogAgent 背景分析使用）
        List<CandidateSnapshot> candidateSnapshots = CandidateSnapshot.toCandidateSnapshots(candidates);

        String reqBody = null;
        String resBody = null;
        if (configService.isRequestLogIncludeBody()) {
            int maxSize = configService.getRequestLogMaxBodySize();
            reqBody = truncateBody(requestBody, maxSize);
            resBody = truncateBody(responseBody, maxSize);
        }

        // 嘗試委派給 LogAgent
        LogAgent agent = logAgentProvider.getIfAvailable();
        if (agent != null && agent.getStatus() == AgentStatus.RUNNING) {
            LogTask task = LogTask.builder()
                    .ruleId(ruleId)
                    .protocol(protocol)
                    .method(method)
                    .endpoint(endpoint)
                    .matched(matched)
                    .responseTimeMs(responseTimeMs)
                    .matchTimeMs(matchTimeMs)
                    .clientIp(clientIp)
                    .requestTime(LocalDateTime.now())
                    .matchChain(matchChain)
                    .targetHost(targetHost)
                    .proxyStatus(proxyStatus)
                    .proxyError(proxyError)
                    .responseStatus(responseStatus)
                    .requestBody(reqBody)
                    .responseBody(resBody)
                    .faultType(faultType)
                    .scenarioName(scenarioName)
                    .scenarioFromState(scenarioFromState)
                    .scenarioToState(scenarioToState)
                    .candidates(candidateSnapshots)
                    .preparedBody(preparedBody)
                    .queryString(queryString)
                    .headers(headers)
                    .build();
            agent.submit(task);
            // 成功委派後重置 fallback 警告標記
            fallbackWarned.set(false);
        } else {
            // 降級為同步寫入
            if (fallbackWarned.compareAndSet(false, true)) {
                log.warn("LogAgent not available or not RUNNING, falling back to synchronous write");
            }

            LogEntry entry = LogEntry.builder()
                    .ruleId(ruleId)
                    .protocol(protocol)
                    .method(method)
                    .endpoint(endpoint)
                    .matched(matched)
                    .responseTimeMs(responseTimeMs)
                    .matchTimeMs(matchTimeMs)
                    .clientIp(clientIp)
                    .requestTime(LocalDateTime.now())
                    .matchChain(matchChain)
                    .targetHost(targetHost)
                    .proxyStatus(proxyStatus)
                    .proxyError(proxyError)
                    .responseStatus(responseStatus)
                    .requestBody(reqBody)
                    .responseBody(resBody)
                    .faultType(faultType)
                    .scenarioName(scenarioName)
                    .scenarioFromState(scenarioFromState)
                    .scenarioToState(scenarioToState)
                    .build();

            directWrite(entry);
        }
    }

    /**
     * 同步直接寫入（降級路徑）。
     * Memory 模式寫入環形緩衝區；Database 模式直接 save 到 DB。
     */
    private void directWrite(LogEntry entry) {
        if (configService.isRequestLogMemoryMode()) {
            if (entry.getId() == null) {
                entry = LogEntry.builder()
                        .id(MEMORY_ID_SEQ.getAndIncrement())
                        .ruleId(entry.getRuleId())
                        .protocol(entry.getProtocol())
                        .method(entry.getMethod())
                        .endpoint(entry.getEndpoint())
                        .matched(entry.isMatched())
                        .responseTimeMs(entry.getResponseTimeMs())
                        .matchTimeMs(entry.getMatchTimeMs())
                        .clientIp(entry.getClientIp())
                        .requestTime(entry.getRequestTime())
                        .matchChain(entry.getMatchChain())
                        .targetHost(entry.getTargetHost())
                        .proxyStatus(entry.getProxyStatus())
                        .proxyError(entry.getProxyError())
                        .responseStatus(entry.getResponseStatus())
                        .requestBody(entry.getRequestBody())
                        .responseBody(entry.getResponseBody())
                        .faultType(entry.getFaultType())
                        .scenarioName(entry.getScenarioName())
                        .scenarioFromState(entry.getScenarioFromState())
                        .scenarioToState(entry.getScenarioToState())
                        .build();
            }
            memoryBuffer.addFirst(entry);
            int size = bufferSize.incrementAndGet();
            while (size > maxRecords) {
                if (memoryBuffer.pollLast() != null) {
                    size = bufferSize.decrementAndGet();
                } else {
                    break;
                }
            }
        } else {
            try {
                requestLogRepository.save(toEntity(entry));
                long count = requestLogRepository.count();
                if (count > maxRecords) {
                    requestLogRepository.deleteOldest((int) (count - maxRecords));
                }
            } catch (Exception e) {
                log.warn("Failed to persist log synchronously: {}", e.getMessage());
            }
        }
    }

    // ===== 查詢方法 =====

    /**
     * 查詢請求記錄（支援篩選）
     */
    public QueryResult query(QueryFilter filter) {
        Stream<LogEntry> stream = configService.isRequestLogMemoryMode()
                ? memoryBuffer.stream()
                : requestLogRepository.findAllByOrderByRequestTimeDesc(PageRequest.of(0, maxRecords))
                        .stream().map(this::toEntry);

        // 篩選
        if (filter.getRuleId() != null) {
            stream = stream.filter(e -> filter.getRuleId().equals(e.getRuleId()));
        }
        if (filter.getProtocol() != null) {
            stream = stream.filter(e -> filter.getProtocol() == e.getProtocol());
        }
        if (filter.getMatched() != null) {
            stream = stream.filter(e -> filter.getMatched() == e.isMatched());
        }
        if (filter.getEndpoint() != null && !filter.getEndpoint().isBlank()) {
            String ep = filter.getEndpoint();
            stream = stream.filter(e -> e.getEndpoint() != null && e.getEndpoint().contains(ep));
        }

        List<LogEntry> entries = stream.toList();
        
        // 批次載入規則資訊 (避免 N+1)
        Set<String> ruleIds = entries.stream().map(LogEntry::getRuleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, RuleSummary> ruleCache = new HashMap<>();
        if (!ruleIds.isEmpty()) {
            protocolHandlerRegistry.findAllByIds(new ArrayList<>(ruleIds))
                    .forEach(r -> ruleCache.put(r.getId(), RuleSummary.from(r)));
        }
        
        List<LogWithRule> results = entries.stream().map(e -> LogWithRule.builder()
                .log(e)
                .rule(e.getRuleId() != null ? ruleCache.get(e.getRuleId()) : null)
                .build()).toList();

        return QueryResult.builder().results(results).build();
    }

    /**
     * 查詢請求記錄摘要（不含 body / matchChain，供列表顯示）
     */
    public SummaryQueryResult querySummary(QueryFilter filter) {
        Stream<LogSummaryEntry> stream;
        if (configService.isRequestLogMemoryMode()) {
            stream = memoryBuffer.stream().map(this::toSummaryFromEntry);
        } else {
            stream = requestLogRepository.findAllByOrderByRequestTimeDesc(PageRequest.of(0, maxRecords))
                    .stream().map(this::toSummaryFromEntity);
        }

        // 篩選
        if (filter.getRuleId() != null) {
            stream = stream.filter(e -> filter.getRuleId().equals(e.getRuleId()));
        }
        if (filter.getProtocol() != null) {
            stream = stream.filter(e -> filter.getProtocol() == e.getProtocol());
        }
        if (filter.getMatched() != null) {
            stream = stream.filter(e -> filter.getMatched() == e.isMatched());
        }
        if (filter.getEndpoint() != null && !filter.getEndpoint().isBlank()) {
            String ep = filter.getEndpoint();
            stream = stream.filter(e -> e.getEndpoint() != null && e.getEndpoint().contains(ep));
        }

        List<LogSummaryEntry> entries = stream.toList();

        // 批次載入規則資訊 (避免 N+1)
        Set<String> ruleIds = entries.stream().map(LogSummaryEntry::getRuleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, RuleSummary> ruleCache = new HashMap<>();
        if (!ruleIds.isEmpty()) {
            protocolHandlerRegistry.findAllByIds(new ArrayList<>(ruleIds))
                    .forEach(r -> ruleCache.put(r.getId(), RuleSummary.from(r)));
        }

        List<LogSummaryWithRule> results = entries.stream().map(e -> LogSummaryWithRule.builder()
                .log(e)
                .rule(e.getRuleId() != null ? ruleCache.get(e.getRuleId()) : null)
                .build()).toList();

        return SummaryQueryResult.builder().results(results).build();
    }

    /**
     * 取得摘要統計（基於保留的記錄）
     */
    public Summary getSummary() {
        long total;
        long matched;
        
        if (configService.isRequestLogMemoryMode()) {
            total = bufferSize.get();
            matched = memoryBuffer.stream().filter(LogEntry::isMatched).count();
        } else {
            total = requestLogRepository.count();
            matched = requestLogRepository.countByMatched(true);
        }
        
        double rate = total > 0 ? (double) matched / total * 100 : 0;
        return Summary.builder()
                .totalRequests(total)
                .matchedRequests(matched)
                .matchRate(Math.round(rate * 10) / 10.0)
                .maxRecords(maxRecords)
                .build();
    }

    /**
     * 取得 memory 模式的環形緩衝區引用，供 LogAgent 在 memory 模式下寫入。
     *
     * @return memory buffer，若非 memory 模式則回傳 null
     */
    public ConcurrentLinkedDeque<LogEntry> getMemoryBuffer() {
        return memoryBuffer;
    }

    /**
     * 取得 memory 模式的 bufferSize 計數器，供 LogAgent 維護環形緩衝區大小。
     *
     * @return bufferSize AtomicInteger
     */
    public AtomicInteger getBufferSize() {
        return bufferSize;
    }

    private String truncateBody(String body, int maxSize) {
        if (body == null || body.length() <= maxSize) {
            return body;
        }
        return body.substring(0, maxSize) + "...(truncated)";
    }

    private RequestLog toEntity(LogEntry e) {
        return RequestLog.builder()
                .ruleId(e.getRuleId())
                .protocol(e.getProtocol())
                .method(e.getMethod())
                .endpoint(e.getEndpoint())
                .matched(e.isMatched())
                .responseTimeMs(e.getResponseTimeMs())
                .matchTimeMs(e.getMatchTimeMs())
                .clientIp(e.getClientIp())
                .requestTime(e.getRequestTime())
                .matchChain(e.getMatchChain())
                .targetHost(e.getTargetHost())
                .proxyStatus(e.getProxyStatus())
                .proxyError(e.getProxyError())
                .responseStatus(e.getResponseStatus())
                .requestBody(e.getRequestBody())
                .responseBody(e.getResponseBody())
                .faultType(e.getFaultType())
                .scenarioName(e.getScenarioName())
                .scenarioFromState(e.getScenarioFromState())
                .scenarioToState(e.getScenarioToState())
                .build();
    }

    private LogEntry toEntry(RequestLog log) {
        return LogEntry.builder()
                .id(log.getId())
                .ruleId(log.getRuleId())
                .protocol(log.getProtocol())
                .method(log.getMethod())
                .endpoint(log.getEndpoint())
                .matched(log.isMatched())
                .responseTimeMs(log.getResponseTimeMs())
                .matchTimeMs(log.getMatchTimeMs())
                .clientIp(log.getClientIp())
                .requestTime(log.getRequestTime())
                .matchChain(log.getMatchChain())
                .targetHost(log.getTargetHost())
                .proxyStatus(log.getProxyStatus())
                .proxyError(log.getProxyError())
                .responseStatus(log.getResponseStatus())
                .requestBody(log.getRequestBody())
                .responseBody(log.getResponseBody())
                .faultType(log.getFaultType())
                .scenarioName(log.getScenarioName())
                .scenarioFromState(log.getScenarioFromState())
                .scenarioToState(log.getScenarioToState())
                .build();
    }

    private LogSummaryEntry toSummaryFromEntry(LogEntry e) {
        return LogSummaryEntry.builder()
                .id(e.getId())
                .ruleId(e.getRuleId())
                .protocol(e.getProtocol())
                .method(e.getMethod())
                .endpoint(e.getEndpoint())
                .matched(e.isMatched())
                .responseTimeMs(e.getResponseTimeMs())
                .matchTimeMs(e.getMatchTimeMs())
                .clientIp(e.getClientIp())
                .requestTime(e.getRequestTime())
                .targetHost(e.getTargetHost())
                .proxyStatus(e.getProxyStatus())
                .proxyError(e.getProxyError())
                .responseStatus(e.getResponseStatus())
                .hasRequestBody(e.getRequestBody() != null && !e.getRequestBody().isBlank())
                .hasResponseBody(e.getResponseBody() != null && !e.getResponseBody().isBlank())
                .hasMatchChain(e.getMatchChain() != null && !e.getMatchChain().isBlank())
                .build();
    }

    private LogSummaryEntry toSummaryFromEntity(RequestLog log) {
        return LogSummaryEntry.builder()
                .id(log.getId())
                .ruleId(log.getRuleId())
                .protocol(log.getProtocol())
                .method(log.getMethod())
                .endpoint(log.getEndpoint())
                .matched(log.isMatched())
                .responseTimeMs(log.getResponseTimeMs())
                .matchTimeMs(log.getMatchTimeMs())
                .clientIp(log.getClientIp())
                .requestTime(log.getRequestTime())
                .targetHost(log.getTargetHost())
                .proxyStatus(log.getProxyStatus())
                .proxyError(log.getProxyError())
                .responseStatus(log.getResponseStatus())
                .hasRequestBody(log.getRequestBody() != null && !log.getRequestBody().isBlank())
                .hasResponseBody(log.getResponseBody() != null && !log.getResponseBody().isBlank())
                .hasMatchChain(log.getMatchChain() != null && !log.getMatchChain().isBlank())
                .build();
    }

    // ===== DTO =====

    @Getter @Builder
    public static class LogEntry {
        private Long id;
        private String ruleId;
        private Protocol protocol;
        private String method;
        private String endpoint;
        private boolean matched;
        private int responseTimeMs;
        private Integer matchTimeMs;
        private String clientIp;
        private LocalDateTime requestTime;
        private String matchChain;
        private String targetHost;
        private Integer proxyStatus;
        private String proxyError;
        private Integer responseStatus;
        private String requestBody;
        private String responseBody;
        private String faultType;
        private String scenarioName;
        private String scenarioFromState;
        private String scenarioToState;
    }

    /**
     * 列表用摘要 DTO — 不含 requestBody / responseBody / matchChain，
     * 減少列表查詢的傳輸量。
     */
    @Getter @Builder
    public static class LogSummaryEntry {
        private Long id;
        private String ruleId;
        private Protocol protocol;
        private String method;
        private String endpoint;
        private boolean matched;
        private int responseTimeMs;
        private Integer matchTimeMs;
        private String clientIp;
        private LocalDateTime requestTime;
        private String targetHost;
        private Integer proxyStatus;
        private String proxyError;
        private Integer responseStatus;
        /** 是否有 requestBody（供前端判斷是否需要 lazy load） */
        private boolean hasRequestBody;
        /** 是否有 responseBody */
        private boolean hasResponseBody;
        /** 是否有 matchChain */
        private boolean hasMatchChain;
    }

    @Getter @Builder
    public static class RuleSummary {
        private String id;
        private String matchKey;
        private String method;
        private String description;

        public static RuleSummary from(BaseRule rule) {
            String matchKey;
            String method;
            if (rule instanceof HttpRule httpRule) {
                matchKey = httpRule.getMatchKey();
                method = httpRule.getMethod();
            } else {
                matchKey = rule.getDescription();
                method = rule.getProtocol().name();
            }
            return RuleSummary.builder()
                    .id(rule.getId())
                    .matchKey(matchKey)
                    .method(method)
                    .description(rule.getDescription())
                    .build();
        }
    }

    @Getter @Builder
    public static class LogWithRule {
        private LogEntry log;
        private RuleSummary rule;
    }

    @Getter @Builder
    public static class LogSummaryWithRule {
        private LogSummaryEntry log;
        private RuleSummary rule;
    }

    @Getter @Builder
    public static class QueryFilter {
        private String ruleId;
        private Protocol protocol;
        private Boolean matched;
        private String endpoint;
    }

    @Getter @Builder
    public static class QueryResult {
        private List<LogWithRule> results;
    }

    @Getter @Builder
    public static class SummaryQueryResult {
        private List<LogSummaryWithRule> results;
    }

    @Getter @Builder
    public static class Summary {
        private long totalRequests;
        private long matchedRequests;
        private double matchRate;
        private int maxRecords;
    }
}
