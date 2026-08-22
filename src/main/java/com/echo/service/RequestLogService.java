package com.echo.service;

import com.echo.agent.CandidateSnapshot;
import com.echo.agent.LogAgent;
import com.echo.agent.LogTask;
import com.echo.entity.BaseRule;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.RequestLogRepository;
import com.echo.repository.RequestLogSummaryQuery;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * Database 模式會先由 LogAgent 寫入獨立 durable spool，再由背景 worker
 * 批次送往主資料庫。只有 spool 確認落地後請求才算成功接受，主資料庫
 * 暫時不可用時不會靜默丟失紀錄。
 */
@Service
@Slf4j
public class RequestLogService {

    private final RequestLogRepository requestLogRepository;
    private final SystemConfigService configService;
    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ObjectProvider<LogAgent> logAgentProvider;
    private final RequestLogSummaryQuery summaryQuery;

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

    /** LogAgent 不可用時只 warn 一次，恢復後才允許再次提示。 */
    private final AtomicBoolean agentUnavailableWarned = new AtomicBoolean(false);

    @Autowired
    public RequestLogService(RequestLogRepository requestLogRepository,
                             SystemConfigService configService,
                             ProtocolHandlerRegistry protocolHandlerRegistry,
                             ObjectProvider<LogAgent> logAgentProvider,
                             RequestLogSummaryQuery summaryQuery) {
        this.requestLogRepository = requestLogRepository;
        this.configService = configService;
        this.protocolHandlerRegistry = protocolHandlerRegistry;
        this.logAgentProvider = logAgentProvider;
        this.summaryQuery = summaryQuery;
    }

    /** Test-compatible constructor retaining the repository mock seam. */
    public RequestLogService(RequestLogRepository requestLogRepository,
                             SystemConfigService configService,
                             ProtocolHandlerRegistry protocolHandlerRegistry,
                             ObjectProvider<LogAgent> logAgentProvider) {
        this(requestLogRepository, configService, protocolHandlerRegistry,
                logAgentProvider, null);
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
                requestBody, responseBody, null, null, null, null);
    }

    /**
     * 記錄請求（含匹配上下文）
     * <p>
     * 當 LogAgent 可用時，延遲建構 LogTask 並委派給 durable spool。
     * Agent 或 spool 不可用時 fail fast，避免回覆成功卻遺失請求紀錄。
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
                       Map<String, String> headers) {
        record(ruleId, protocol, method, endpoint, matched, responseTimeMs, clientIp,
                matchChain, targetHost, proxyStatus, proxyError, responseStatus, matchTimeMs,
                requestBody, responseBody, candidates, preparedBody, queryString, headers,
                null, null, null, null);
    }

    /** 記錄請求，並保留故障注入與 Scenario 狀態轉移資訊。 */
    @SuppressWarnings("java:S107")
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
        record(ruleId, protocol, method, endpoint, matched, responseTimeMs, clientIp,
                matchChain, targetHost, false, null, proxyStatus, proxyError,
                responseStatus, matchTimeMs, requestBody, responseBody, candidates,
                preparedBody, queryString, headers, faultType, scenarioName,
                scenarioFromState, scenarioToState);
    }

    /** 記錄請求，並保留轉發、故障注入與 Scenario 狀態轉移資訊。 */
    @SuppressWarnings("java:S107")
    public <T extends BaseRule> void record(String ruleId, Protocol protocol, String method, String endpoint,
                       boolean matched, int responseTimeMs, String clientIp,
                       String matchChain, String targetHost,
                       boolean forwarded, String forwardTarget,
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
        LogAgent agent = logAgentProvider.getIfAvailable();
        if (agent == null) {
            if (agentUnavailableWarned.compareAndSet(false, true)) {
                log.warn("LogAgent unavailable; requests cannot be durably acknowledged");
            }
            throw new RequestLogUnavailableException("Request-log agent is unavailable");
        }
        agentUnavailableWarned.set(false);
        agent.submitDurably(() -> buildLogTask(
                ruleId, protocol, method, endpoint, matched, responseTimeMs, clientIp,
                matchChain, targetHost, forwarded, forwardTarget,
                proxyStatus, proxyError, responseStatus, matchTimeMs,
                requestBody, responseBody, candidates, preparedBody, queryString, headers,
                faultType, scenarioName, scenarioFromState, scenarioToState));
    }

    private <T extends BaseRule> LogTask buildLogTask(
            String ruleId, Protocol protocol, String method, String endpoint,
            boolean matched, int responseTimeMs, String clientIp,
            String matchChain, String targetHost, boolean forwarded, String forwardTarget,
            Integer proxyStatus, String proxyError,
            Integer responseStatus, Integer matchTimeMs, String requestBody, String responseBody,
            List<T> candidates, ConditionMatcher.PreparedBody preparedBody,
            String queryString, Map<String, String> headers,
            String faultType, String scenarioName,
            String scenarioFromState, String scenarioToState) {
        List<CandidateSnapshot> candidateSnapshots = CandidateSnapshot.toCandidateSnapshots(candidates);
        String reqBody = null;
        String resBody = null;
        int maxSize = configService.getRequestLogMaxBodySize();
        if (configService.isRequestLogIncludeBody()) {
            reqBody = truncateBody(requestBody, maxSize);
            resBody = truncateBody(responseBody, maxSize);
        }
        String rawAnalysisBody = preparedBody != null ? preparedBody.getRaw() : requestBody;
        // Detailed near-miss analysis is optional. Never retain an oversized source body
        // merely for diagnostics; the request-time match chain remains authoritative.
        String analysisBody = rawAnalysisBody != null && rawAnalysisBody.length() <= maxSize
                ? rawAnalysisBody : null;
        return LogTask.builder()
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
                .forwarded(forwarded)
                .forwardTarget(RequestLog.limitForwardTarget(forwardTarget))
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
                // Never retain PreparedBody: XML PreparedBody owns a full DOM and caused
                // queued log tasks to pin hundreds of MiB. Persist only source text and
                // reconstruct parsed state inside the background analysis worker.
                .analysisBody(analysisBody)
                .queryString(queryString)
                .headers(headers)
                .matchOutcomes(preparedBody != null
                        ? preparedBody.getMatchOutcomesSnapshot() : Map.of())
                .build();
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
            String keyword = filter.getEndpoint().toLowerCase(Locale.ROOT);
            stream = stream.filter(e -> containsIgnoreCase(e.getEndpoint(), keyword)
                    || containsIgnoreCase(e.getTargetHost(), keyword)
                    || containsIgnoreCase(e.getForwardTarget(), keyword)
                    || containsIgnoreCase(e.getRuleId(), keyword));
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
        int pageNumber = Math.max(0, filter.getPage() != null ? filter.getPage() : 0);
        int pageSize = Math.max(1, Math.min(filter.getSize() != null ? filter.getSize() : maxRecords, maxRecords));
        String sortField = normalizeSortField(filter.getSortField());
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(filter.getSortDirection())
                ? Sort.Direction.ASC : Sort.Direction.DESC;

        List<LogSummaryEntry> entries;
        long totalElements;
        int totalPages;
        if (configService.isRequestLogMemoryMode()) {
            List<LogSummaryEntry> filtered = applySummaryFilters(
                    memoryBuffer.stream().map(this::toSummaryFromEntry), filter)
                    .sorted(summaryComparator(sortField, sortDirection))
                    .toList();
            totalElements = filtered.size();
            totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / pageSize);
            long offset = (long) pageNumber * pageSize;
            int fromIndex = offset >= filtered.size() ? filtered.size() : (int) offset;
            int toIndex = Math.min(fromIndex + pageSize, filtered.size());
            entries = filtered.subList(fromIndex, toIndex);
        } else {
            String endpoint = normalizeEndpoint(filter.getEndpoint());
            Sort pageSort = Sort.by(sortDirection, sortField)
                    .and(Sort.by(sortDirection, "id"));
            if (summaryQuery != null) {
                RequestLogSummaryQuery.Result resultPage = summaryQuery.query(
                        new RequestLogSummaryQuery.Filter(
                                filter.getRuleId(), filter.getProtocol(), filter.getMatched(),
                                endpoint, filter.getAfterId()),
                        pageNumber, pageSize, sortField,
                        sortDirection == Sort.Direction.ASC);
                entries = resultPage.rows().stream().map(this::toSummaryFromRow).toList();
                totalElements = resultPage.totalElements();
                totalPages = resultPage.totalPages();
            } else {
                Page<Object[]> resultPage = requestLogRepository.findSummaryPage(
                        filter.getRuleId(), filter.getProtocol(), filter.getMatched(), endpoint,
                        filter.getAfterId(), PageRequest.of(pageNumber, pageSize, pageSort));
                entries = resultPage.getContent().stream().map(this::toSummaryFromProjection).toList();
                totalElements = resultPage.getTotalElements();
                totalPages = resultPage.getTotalPages();
            }
        }

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

        Long newestId = entries.stream().map(LogSummaryEntry::getId)
                .filter(Objects::nonNull).max(Long::compareTo).orElse(null);
        return SummaryQueryResult.builder()
                .results(results)
                .page(pageNumber)
                .size(pageSize)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .newestId(newestId)
                .build();
    }

    private Stream<LogSummaryEntry> applySummaryFilters(Stream<LogSummaryEntry> stream, QueryFilter filter) {
        if (filter.getRuleId() != null) {
            stream = stream.filter(e -> filter.getRuleId().equals(e.getRuleId()));
        }
        if (filter.getProtocol() != null) {
            stream = stream.filter(e -> filter.getProtocol() == e.getProtocol());
        }
        if (filter.getMatched() != null) {
            stream = stream.filter(e -> filter.getMatched() == e.isMatched());
        }
        String endpoint = normalizeEndpoint(filter.getEndpoint());
        if (endpoint != null) {
            String normalized = endpoint.toLowerCase(Locale.ROOT);
            stream = stream.filter(e -> containsIgnoreCase(e.getEndpoint(), normalized)
                    || containsIgnoreCase(e.getTargetHost(), normalized)
                    || containsIgnoreCase(e.getForwardTarget(), normalized)
                    || containsIgnoreCase(e.getRuleId(), normalized));
        }
        if (filter.getAfterId() != null) {
            stream = stream.filter(e -> e.getId() != null && e.getId() > filter.getAfterId());
        }
        return stream;
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private String normalizeEndpoint(String endpoint) {
        return endpoint == null || endpoint.isBlank() ? null : endpoint.trim();
    }

    private String normalizeSortField(String sortField) {
        return switch (sortField == null ? "requestTime" : sortField) {
            case "endpoint" -> "endpoint";
            case "responseTimeMs" -> "responseTimeMs";
            default -> "requestTime";
        };
    }

    private Comparator<LogSummaryEntry> summaryComparator(String sortField, Sort.Direction direction) {
        Comparator<LogSummaryEntry> comparator = switch (sortField) {
            case "endpoint" -> Comparator.comparing(LogSummaryEntry::getEndpoint,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "responseTimeMs" -> Comparator.comparingInt(LogSummaryEntry::getResponseTimeMs);
            default -> Comparator.comparing(LogSummaryEntry::getRequestTime,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        comparator = comparator.thenComparing(LogSummaryEntry::getId,
                Comparator.nullsLast(Comparator.naturalOrder()));
        return direction == Sort.Direction.DESC ? comparator.reversed() : comparator;
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
                .forwarded(log.isForwarded())
                .forwardTarget(log.getForwardTarget())
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
                .forwarded(e.isForwarded())
                .forwardTarget(e.getForwardTarget())
                .proxyStatus(e.getProxyStatus())
                .proxyError(e.getProxyError())
                .responseStatus(e.getResponseStatus())
                .faultType(e.getFaultType())
                .scenarioName(e.getScenarioName())
                .scenarioFromState(e.getScenarioFromState())
                .scenarioToState(e.getScenarioToState())
                .hasRequestBody(e.getRequestBody() != null && !e.getRequestBody().isBlank())
                .hasResponseBody(e.getResponseBody() != null && !e.getResponseBody().isBlank())
                .hasMatchChain(e.getMatchChain() != null && !e.getMatchChain().isBlank())
                .build();
    }

    private LogSummaryEntry toSummaryFromProjection(Object[] row) {
        return LogSummaryEntry.builder()
                .id((Long) row[0])
                .ruleId((String) row[1])
                .protocol((Protocol) row[2])
                .method((String) row[3])
                .endpoint((String) row[4])
                .matched(Boolean.TRUE.equals(row[5]))
                .responseTimeMs(row[6] != null ? ((Number) row[6]).intValue() : 0)
                .matchTimeMs(row[7] != null ? ((Number) row[7]).intValue() : null)
                .clientIp((String) row[8])
                .requestTime((LocalDateTime) row[9])
                .targetHost((String) row[10])
                .forwarded(Boolean.TRUE.equals(row[11]))
                .forwardTarget((String) row[12])
                .proxyStatus(row[13] != null ? ((Number) row[13]).intValue() : null)
                .proxyError((String) row[14])
                .responseStatus(row[15] != null ? ((Number) row[15]).intValue() : null)
                .faultType((String) row[16])
                .scenarioName((String) row[17])
                .scenarioFromState((String) row[18])
                .scenarioToState((String) row[19])
                .hasRequestBody(Boolean.TRUE.equals(row[20]))
                .hasResponseBody(Boolean.TRUE.equals(row[21]))
                .hasMatchChain(Boolean.TRUE.equals(row[22]))
                .build();
    }

    private LogSummaryEntry toSummaryFromRow(RequestLogSummaryQuery.SummaryRow row) {
        return LogSummaryEntry.builder()
                .id(row.id())
                .ruleId(row.ruleId())
                .protocol(row.protocol())
                .method(row.method())
                .endpoint(row.endpoint())
                .matched(Boolean.TRUE.equals(row.matched()))
                .responseTimeMs(row.responseTimeMs() != null ? row.responseTimeMs().intValue() : 0)
                .matchTimeMs(row.matchTimeMs() != null ? row.matchTimeMs().intValue() : null)
                .clientIp(row.clientIp())
                .requestTime(row.requestTime())
                .targetHost(row.targetHost())
                .forwarded(Boolean.TRUE.equals(row.forwarded()))
                .forwardTarget(row.forwardTarget())
                .proxyStatus(row.proxyStatus() != null ? row.proxyStatus().intValue() : null)
                .proxyError(row.proxyError())
                .responseStatus(row.responseStatus() != null ? row.responseStatus().intValue() : null)
                .faultType(row.faultType())
                .scenarioName(row.scenarioName())
                .scenarioFromState(row.scenarioFromState())
                .scenarioToState(row.scenarioToState())
                .hasRequestBody(Boolean.TRUE.equals(row.hasRequestBody()))
                .hasResponseBody(Boolean.TRUE.equals(row.hasResponseBody()))
                .hasMatchChain(Boolean.TRUE.equals(row.hasMatchChain()))
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
        private boolean forwarded;
        private String forwardTarget;
        private Integer proxyStatus;
        private String proxyError;
        private Integer responseStatus;
        private String faultType;
        private String scenarioName;
        private String scenarioFromState;
        private String scenarioToState;
        private String requestBody;
        private String responseBody;
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
        private boolean forwarded;
        private String forwardTarget;
        private Integer proxyStatus;
        private String proxyError;
        private Integer responseStatus;
        private String faultType;
        private String scenarioName;
        private String scenarioFromState;
        private String scenarioToState;
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
        private Integer page;
        private Integer size;
        private String sortField;
        private String sortDirection;
        private Long afterId;
    }

    @Getter @Builder
    public static class QueryResult {
        private List<LogWithRule> results;
    }

    @Getter @Builder
    public static class SummaryQueryResult {
        private List<LogSummaryWithRule> results;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private Long newestId;
    }

    @Getter @Builder
    public static class Summary {
        private long totalRequests;
        private long matchedRequests;
        private double matchRate;
        private int maxRecords;
    }
}
