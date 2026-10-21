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
 * Request logging service.
 * 
 * Use {@code echo.request-log.enabled=false} to completely stop new request records.
 * Two storage modes are supported when enabled:
 * - memory: an in-memory ring buffer.
 * - database: persist to the database and periodically remove records beyond max-records.
 *
 * In database mode, LogAgent first writes to an independent durable spool; a background worker then
 * persists batches to the main database. In strict mode, acceptance waits for the spool commit, so a temporary main-database
 * outage cannot silently lose accepted records.
 */
@Service
@Slf4j
public class RequestLogService implements ServeEventListener {

    private final RequestLogRepository requestLogRepository;
    private final SystemConfigService configService;
    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ObjectProvider<LogAgent> logAgentProvider;
    private final RequestLogSummaryQuery summaryQuery;

    // ===== Memory Mode: Ring Buffer =====
    private ConcurrentLinkedDeque<LogEntry> memoryBuffer;
    private final AtomicInteger bufferSize = new AtomicInteger(0);
    private volatile int maxRecords;

    /** ID generator for Memory mode */
    private static final AtomicLong MEMORY_ID_SEQ = new AtomicLong(1);

    /** Get the next Memory mode ID (for use by LogAgent) */
    public static long nextMemoryId() {
        return MEMORY_ID_SEQ.getAndIncrement();
    }

    /** Warn once while LogAgent is unavailable; allow another warning after recovery. */
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
        }
        if (configService.isRequestLogDisabled()) {
            log.info("Request logging is disabled; existing logs remain queryable");
        } else if (configService.isRequestLogMemoryMode()) {
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
     * Query single request record according to ID
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
     * Log a request.
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
     * Log a request with matching context.
     * <p>
     * Build LogTask lazily when LogAgent is available; by default return after buffering and drop the oldest records if writes cannot keep up.
     * With {@code echo.request-log.wait-for-write=true}, wait for the durable-spool write and fail fast if it cannot be written.
     * fail fast when Agent does not exist.
     * Explicitly disabling request logging returns before obtaining Agent or creating any logging data.
     */
    @SuppressWarnings("java:S107") // The large number of parameters is due to the need to pass the complete matching context
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

    /** Log the request, including fault injection and Scenario state-transition information. */
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

    /** Log the request, including forwarding, fault injection, and Scenario state-transition information. */
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
        record(ruleId, protocol, method, endpoint, matched, responseTimeMs, clientIp,
                matchChain, targetHost, forwarded, forwardTarget, proxyStatus, proxyError,
                responseStatus, matchTimeMs, requestBody, responseBody, candidates, preparedBody,
                queryString, headers, faultType, scenarioName, scenarioFromState, scenarioToState, null);
    }

    /** Log the request, including forwarding, fault injection, and Scenario state-transition information. */
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
                       String scenarioToState, String diagnosticId) {
        record(ServeEvent.builder()
                .ruleId(ruleId).protocol(protocol).method(method).endpoint(endpoint)
                .matched(matched).responseTimeMs(responseTimeMs).clientIp(clientIp)
                .matchChain(matchChain).targetHost(targetHost)
                .forwarded(forwarded).forwardTarget(forwardTarget)
                .proxyStatus(proxyStatus).proxyError(proxyError)
                .responseStatus(responseStatus).matchTimeMs(matchTimeMs)
                .requestBody(requestBody).responseBody(responseBody)
                .candidates(candidates).preparedBody(preparedBody)
                .queryString(queryString).headers(headers).faultType(faultType)
                .scenarioName(scenarioName).scenarioFromState(scenarioFromState).scenarioToState(scenarioToState)
                .diagnosticId(diagnosticId)
                .build());
    }

    /**
     * Critical request-log listener: propagate admission failures, producing HTTP 503 or failing JMS delivery.
     * With the default {@code echo.request-log.wait-for-write=false}, this occurs only when the logging component is missing.
     */
    @Override
    public void onServed(ServeEvent event) {
        record(event);
    }

    @Override
    public boolean requiresFullResponseBody() {
        return false;
    }

    @Override
    public boolean critical() {
        return true;
    }

    /**
     * Record a processed request.
     * <p>
     * Build LogTask lazily and delegate to the durable spool when LogAgent is available.
     * Fail fast if the agent or spool is unavailable, avoiding a successful reply with a silently lost request log.
     * Explicitly disabling request logging returns before obtaining Agent or creating any logging data.
     */
    public void record(ServeEvent event) {
        if (configService.isRequestLogDisabled()) {
            return;
        }
        LogAgent agent = logAgentProvider.getIfAvailable();
        if (agent == null) {
            if (agentUnavailableWarned.compareAndSet(false, true)) {
                log.warn("LogAgent unavailable; requests cannot be durably acknowledged");
            }
            throw new RequestLogUnavailableException("Request-log agent is unavailable");
        }
        agentUnavailableWarned.set(false);
        agent.submitDurably(() -> buildLogTask(event));
    }

    private LogTask buildLogTask(ServeEvent event) {
        String requestBody = event.getRequestBody();
        ConditionMatcher.PreparedBody preparedBody = event.getPreparedBody();
        List<CandidateSnapshot> candidateSnapshots = CandidateSnapshot.toCandidateSnapshots(event.getCandidates());
        String reqBody = null;
        String resBody = null;
        int maxSize = configService.getRequestLogMaxBodySize();
        if (configService.isRequestLogIncludeBody()) {
            reqBody = truncateBody(requestBody, maxSize);
            resBody = truncateBody(event.responseBodyForCapture(maxSize), maxSize);
        }
        String rawAnalysisBody = preparedBody != null ? preparedBody.getRaw() : requestBody;
        // Detailed near-miss analysis is optional. Never retain an oversized source body
        // merely for diagnostics; the request-time match chain remains authoritative.
        String analysisBody = rawAnalysisBody != null && rawAnalysisBody.length() <= maxSize
                ? rawAnalysisBody : null;
        return LogTask.builder()
                .diagnosticId(event.getDiagnosticId())
                .ruleId(event.getRuleId())
                .protocol(event.getProtocol())
                .method(event.getMethod())
                .endpoint(event.getEndpoint())
                .matched(event.isMatched())
                .responseTimeMs(event.getResponseTimeMs())
                .matchTimeMs(event.getMatchTimeMs())
                .clientIp(event.getClientIp())
                .requestTime(LocalDateTime.now())
                .matchChain(event.getMatchChain())
                .targetHost(event.getTargetHost())
                .forwarded(event.isForwarded())
                .forwardTarget(RequestLog.limitForwardTarget(event.getForwardTarget()))
                .proxyStatus(event.getProxyStatus())
                // Match the existing DB column, without changing the actual response body.
                .proxyError(RequestLog.limitProxyError(event.getProxyError()))
                .responseStatus(event.getResponseStatus())
                .requestBody(reqBody)
                .responseBody(resBody)
                .faultType(event.getFaultType())
                .scenarioName(event.getScenarioName())
                .scenarioFromState(event.getScenarioFromState())
                .scenarioToState(event.getScenarioToState())
                .candidateCount(event.getCandidateCount())
                .matchedByFallback(event.getMatchedByFallback())
                .delayMs(event.getDelayMs())
                .sseEventCount(event.getSseEventCount())
                .scenarioSkipped(event.getScenarioSkipped())
                .candidates(candidateSnapshots)
                // Never retain PreparedBody: XML PreparedBody owns a full DOM and caused
                // queued log tasks to pin hundreds of MiB. Persist only source text and
                // reconstruct parsed state inside the background analysis worker.
                .analysisBody(analysisBody)
                .queryString(event.getQueryString())
                .headers(event.getHeaders())
                .matchOutcomes(preparedBody != null
                        ? preparedBody.getMatchOutcomesSnapshot() : Map.of())
                .build();
    }

    // ===== Query method =====

    /**
     * Query request records (supports filtering)
     */
    public QueryResult query(QueryFilter filter) {
        Stream<LogEntry> stream = configService.isRequestLogMemoryMode()
                ? memoryBuffer.stream()
                : requestLogRepository.findAllByOrderByRequestTimeDesc(PageRequest.of(0, maxRecords))
                        .stream().map(this::toEntry);

        // Filter
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
                    || containsIgnoreCase(e.getDiagnosticId(), keyword)
                    || containsIgnoreCase(e.getRuleId(), keyword));
        }

        List<LogEntry> entries = stream.toList();
        
        // Batch loading rule information (avoid N+1)
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
     * Query request record summary (excluding body / matchChain, for list display)
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
                                endpoint, filter.getAfterId(), filter.getFrom(), filter.getTo(),
                                blankToNull(filter.getMethod()), blankToNull(filter.getPath())),
                        pageNumber, pageSize, sortField,
                        sortDirection == Sort.Direction.ASC);
                entries = resultPage.rows().stream().map(this::toSummaryFromRow).toList();
                totalElements = resultPage.totalElements();
                totalPages = resultPage.totalPages();
            } else {
                // Compatibility constructor retaining the repository test seam; production uses summaryQuery above.
                Page<Object[]> resultPage = requestLogRepository.findSummaryPage(
                        filter.getRuleId(), filter.getProtocol(), filter.getMatched(), endpoint,
                        filter.getAfterId(), filter.getFrom(), filter.getTo(),
                        PageRequest.of(pageNumber, pageSize, pageSort));
                entries = resultPage.getContent().stream().map(this::toSummaryFromProjection).toList();
                totalElements = resultPage.getTotalElements();
                totalPages = resultPage.getTotalPages();
            }
        }

        // Batch loading rule information (avoid N+1)
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
                    || containsIgnoreCase(e.getDiagnosticId(), normalized)
                    || containsIgnoreCase(e.getRuleId(), normalized));
        }
        String method = blankToNull(filter.getMethod());
        if (method != null) {
            stream = stream.filter(e -> method.equalsIgnoreCase(e.getMethod()));
        }
        String path = blankToNull(filter.getPath());
        if (path != null) {
            stream = stream.filter(e -> path.equals(e.getEndpoint()));
        }
        if (filter.getAfterId() != null) {
            stream = stream.filter(e -> e.getId() != null && e.getId() > filter.getAfterId());
        }
        if (filter.getFrom() != null) {
            stream = stream.filter(e -> e.getRequestTime() != null && !e.getRequestTime().isBefore(filter.getFrom()));
        }
        if (filter.getTo() != null) {
            stream = stream.filter(e -> e.getRequestTime() != null && e.getRequestTime().isBefore(filter.getTo()));
        }
        return stream;
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
     * Get summary statistics (based on retained records)
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
     * Obtain the ring buffer reference in memory mode for LogAgent to write in memory mode.
     *
     * @return The memory buffer, or null outside memory mode.
     */
    public ConcurrentLinkedDeque<LogEntry> getMemoryBuffer() {
        return memoryBuffer;
    }

    /**
     * Get the bufferSize counter in memory mode for LogAgent to maintain the ring buffer size.
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
                .diagnosticId(log.getDiagnosticId())
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
                .candidateCount(log.getCandidateCount())
                .matchedByFallback(log.getMatchedByFallback())
                .delayMs(log.getDelayMs())
                .sseEventCount(log.getSseEventCount())
                .scenarioSkipped(log.getScenarioSkipped())
                .build();
    }

    private LogSummaryEntry toSummaryFromEntry(LogEntry e) {
        return LogSummaryEntry.builder()
                .diagnosticId(e.getDiagnosticId())
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
                .diagnosticId(row.length > 23 ? (String) row[23] : null)
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
                .diagnosticId(row.diagnosticId())
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
        private String diagnosticId;
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
        private Integer candidateCount;
        private Boolean matchedByFallback;
        private Integer delayMs;
        private Integer sseEventCount;
        private String scenarioSkipped;
        private String requestBody;
        private String responseBody;
    }

    /**
     * Summary for list DTO — excluding requestBody / responseBody / matchChain,
     * Reduce transfer volume for list queries.
     */
    @Getter @Builder
    public static class LogSummaryEntry {
        private Long id;
        private String diagnosticId;
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
        /** Whether there is requestBody (for the front end to determine whether lazy load is needed) */
        private boolean hasRequestBody;
        /** Is there responseBody */
        private boolean hasResponseBody;
        /** Is there matchChain */
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
        /** HTTP method, matched exactly ignoring case. */
        private String method;
        /** URL, matched exactly; {@link #endpoint} provides substring matching instead. */
        private String path;
        private Integer page;
        private Integer size;
        private String sortField;
        private String sortDirection;
        private Long afterId;
        /** Lower limit of request time (inclusive). */
        private LocalDateTime from;
        /** Exclusive request-time upper bound. */
        private LocalDateTime to;
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
