package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchDescriptionBuilder;
import com.echo.service.RequestLogBatchWriter;
import com.echo.service.RequestLogService;
import com.echo.service.RequestLogUnavailableException;
import com.echo.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Log Agent — 非同步批次寫入請求日誌。
 * <p>
 * 支援兩種儲存模式：
 * <ul>
 *   <li>Database 模式：批次 saveAll + 超過 max-records 時 deleteOldest</li>
 *   <li>Memory 模式：寫入 RequestLogService 的 memory ring buffer</li>
 * </ul>
 * <p>
 * 當 {@code echo.agent.analysis.enabled=true} 時，在正常負載下於背景執行匹配鏈分析；
 * queue 積壓時會暫停這項可選分析，優先保護 Mock／轉發流量。
 */
@Component
@Slf4j
public class LogAgent extends AbstractBatchAgent<LogTask> {

    private static final String AGENT_NAME = "log-agent";
    private static final long DURABLE_CLEANUP_INTERVAL = 1_000;

    private final RequestLogRepository requestLogRepository;
    private final SystemConfigService configService;
    private final ConditionMatcher conditionMatcher;
    private final RequestLogService requestLogService;
    private final RequestLogSpool durableSpool;
    private final RequestLogBatchWriter durableBatchWriter;

    private final boolean analysisEnabled;
    private final int maxWriteRatePerSecond;
    private final int durableBatchSize;
    private final AtomicBoolean analysisShedUntilQueueDrained = new AtomicBoolean(false);
    private final AtomicBoolean durableConsumerRunning = new AtomicBoolean(false);
    private final Semaphore durableWorkAvailable = new Semaphore(0);
    private ExecutorService durableConsumerExecutor;

    @Autowired
    public LogAgent(
            RequestLogRepository requestLogRepository,
            SystemConfigService configService,
            ConditionMatcher conditionMatcher,
            RequestLogService requestLogService,
            @Value("${echo.agent.log.queue-capacity:500}") int queueCapacity,
            @Value("${echo.agent.log.batch-size:50}") int batchSize,
            @Value("${echo.agent.log.flush-interval-seconds:5}") int flushIntervalSeconds,
            @Value("${echo.agent.analysis.enabled:true}") boolean analysisEnabled,
            @Value("${echo.agent.log.max-write-rate-per-second:1000}") int maxWriteRatePerSecond,
            ObjectProvider<RequestLogSpool> durableSpoolProvider,
            RequestLogBatchWriter durableBatchWriter) {
        this(requestLogRepository, configService, conditionMatcher, requestLogService,
                durableSpoolProvider.getIfAvailable(), durableBatchWriter,
                queueCapacity, batchSize, flushIntervalSeconds,
                analysisEnabled, maxWriteRatePerSecond);
    }

    /** Package-private constructor retained for focused unit/property tests. */
    LogAgent(
            RequestLogRepository requestLogRepository,
            SystemConfigService configService,
            ConditionMatcher conditionMatcher,
            RequestLogService requestLogService,
            int queueCapacity,
            int batchSize,
            int flushIntervalSeconds,
            boolean analysisEnabled) {
        this(requestLogRepository, configService, conditionMatcher, requestLogService,
                null, null, queueCapacity, batchSize, flushIntervalSeconds,
                analysisEnabled, 1000);
    }

    /** Package-private durable constructor for failure/recovery tests. */
    LogAgent(
            RequestLogRepository requestLogRepository,
            SystemConfigService configService,
            ConditionMatcher conditionMatcher,
            RequestLogService requestLogService,
            RequestLogSpool durableSpool,
            RequestLogBatchWriter durableBatchWriter,
            int queueCapacity,
            int batchSize,
            int flushIntervalSeconds,
            boolean analysisEnabled,
            int maxWriteRatePerSecond) {
        super(queueCapacity, batchSize, flushIntervalSeconds);
        this.requestLogRepository = requestLogRepository;
        this.configService = configService;
        this.conditionMatcher = conditionMatcher;
        this.requestLogService = requestLogService;
        this.durableSpool = durableSpool;
        this.durableBatchWriter = durableBatchWriter;
        this.analysisEnabled = analysisEnabled;
        this.maxWriteRatePerSecond = Math.max(1, maxWriteRatePerSecond);
        this.durableBatchSize = Math.max(1, batchSize);
    }

    @PostConstruct
    public void init() {
        start();
        if (!configService.isRequestLogMemoryMode()) {
            startDurableConsumer();
        }
    }

    @PreDestroy
    public void destroy() {
        stopDurableConsumer();
        shutdown();
    }

    /**
     * Accepts a request log only after the database-mode spool has durably committed it.
     * Memory mode retains its explicitly best-effort in-memory behavior.
     */
    public void submitDurably(Supplier<? extends LogTask> taskSupplier) {
        Objects.requireNonNull(taskSupplier, "taskSupplier");
        if (getStatus() != AgentStatus.RUNNING) {
            throw new RequestLogUnavailableException("Request-log agent is not running");
        }

        if (configService.isRequestLogMemoryMode() || durableSpool == null) {
            if (!submitLazy(taskSupplier)) {
                throw new RequestLogUnavailableException("Request-log agent cannot accept the task");
            }
            return;
        }

        LogTask task;
        try {
            task = Objects.requireNonNull(taskSupplier.get(), "request log task");
        } catch (RequestLogUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RequestLogUnavailableException("Cannot create request-log task", e);
        }
        durableSpool.append(task);
        durableWorkAvailable.release();
    }

    @Override
    public String getName() {
        return AGENT_NAME;
    }

    @Override
    public AgentStats getStats() {
        AgentStats base = super.getStats();
        if (durableSpool == null || configService.isRequestLogMemoryMode()) {
            return base;
        }
        return AgentStats.builder()
                .queueSize((int) Math.min(Integer.MAX_VALUE, durableSpool.pendingItems()))
                .processedCount(base.getProcessedCount())
                .droppedCount(base.getDroppedCount())
                .build();
    }

    @Override
    public String getDescription() {
        return "Async batch log writer with match chain analysis";
    }

    @Override
    protected void processBatch(List<LogTask> batch) {
        // Detailed re-analysis is useful for diagnostics, but it repeats matching work.
        // Under sustained load retain the request log and its original match chain while
        // shedding only this optional detail, so Mock / forwarding remains the priority.
        // Keep shedding until the backlog is fully drained; otherwise expensive XML tasks
        // would be re-analysed as soon as the queue fell just below the pressure threshold.
        if (isQueueUnderPressure()) {
            analysisShedUntilQueueDrained.set(true);
        } else if (isQueueEmpty()) {
            analysisShedUntilQueueDrained.set(false);
        }
        boolean detailedAnalysis = analysisEnabled && !analysisShedUntilQueueDrained.get();
        if (configService.isRequestLogMemoryMode()) {
            processMemoryMode(batch, detailedAnalysis);
        } else {
            processDatabaseMode(batch, detailedAnalysis);
        }
    }

    @Override
    protected void afterBatchProcessed(int itemCount, long processingNanos) {
        long remainingNanos = calculateThrottleNanos(
                itemCount, processingNanos, maxWriteRatePerSecond);
        if (remainingNanos > 0 && !Thread.currentThread().isInterrupted()) {
            LockSupport.parkNanos(remainingNanos);
        }
    }

    static long calculateThrottleNanos(int itemCount, long processingNanos, int maxRatePerSecond) {
        long targetNanos = ((long) itemCount * 1_000_000_000L + maxRatePerSecond - 1)
                / maxRatePerSecond;
        return Math.max(0, targetNanos - processingNanos);
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
    private void processDatabaseMode(List<LogTask> batch, boolean detailedAnalysis) {
        try {
            List<RequestLog> entities = batch.stream()
                    .map(task -> toEntity(task, detailedAnalysis))
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

    private void startDurableConsumer() {
        if (durableSpool == null || durableBatchWriter == null
                || !durableConsumerRunning.compareAndSet(false, true)) {
            return;
        }
        durableConsumerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "request-log-persistence-worker");
            thread.setDaemon(true);
            return thread;
        });
        durableConsumerExecutor.execute(this::durableConsumeLoop);
    }

    private void stopDurableConsumer() {
        if (!durableConsumerRunning.compareAndSet(true, false)) {
            return;
        }
        durableWorkAvailable.release();
        if (durableConsumerExecutor != null) {
            durableConsumerExecutor.shutdownNow();
            try {
                durableConsumerExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Replays ordered spool rows. Main-DB commit and checkpoint are atomic; spool
     * cleanup happens only afterwards and is therefore safe to retry after a crash.
     */
    private void durableConsumeLoop() {
        Long checkpoint = null;
        long lastCleanedCheckpoint = 0;
        while (durableConsumerRunning.get()) {
            try {
                if (checkpoint == null) {
                    checkpoint = durableBatchWriter.findCheckpoint(durableSpool.getSpoolId());
                    durableSpool.deleteThrough(checkpoint);
                    lastCleanedCheckpoint = checkpoint;
                }

                List<RequestLogSpool.SpoolEntry> fetched =
                        durableSpool.readAfter(checkpoint, durableBatchSize + 1);
                if (fetched.isEmpty()) {
                    if (checkpoint > lastCleanedCheckpoint) {
                        durableSpool.deleteThrough(checkpoint);
                        lastCleanedCheckpoint = checkpoint;
                    }
                    awaitDurableWork();
                    continue;
                }

                boolean hasBacklog = fetched.size() > durableBatchSize;
                List<RequestLogSpool.SpoolEntry> batch = hasBacklog
                        ? fetched.subList(0, durableBatchSize) : fetched;
                boolean detailedAnalysis = analysisEnabled && !hasBacklog;
                long startedAt = System.nanoTime();
                List<RequestLog> entities = batch.stream()
                        .map(entry -> toEntity(entry.task(), detailedAnalysis))
                        .toList();
                long lastSequence = batch.get(batch.size() - 1).sequence();
                durableBatchWriter.persist(durableSpool.getSpoolId(), lastSequence,
                        entities, configService.getRequestLogMaxRecords());
                checkpoint = lastSequence;
                if (checkpoint - lastCleanedCheckpoint >= DURABLE_CLEANUP_INTERVAL) {
                    durableSpool.deleteThrough(checkpoint);
                    lastCleanedCheckpoint = checkpoint;
                }
                recordProcessed(batch.size());
                afterBatchProcessed(batch.size(), System.nanoTime() - startedAt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Durable request-log persistence paused; data remains in spool: {}",
                        e.getMessage());
                try {
                    awaitDurableWork();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void awaitDurableWork() throws InterruptedException {
        if (durableWorkAvailable.tryAcquire(250, TimeUnit.MILLISECONDS)) {
            durableWorkAvailable.drainPermits();
        }
    }

    /**
     * Memory 模式：寫入 RequestLogService 的 memory ring buffer。
     */
    private void processMemoryMode(List<LogTask> batch, boolean detailedAnalysis) {
        ConcurrentLinkedDeque<RequestLogService.LogEntry> buffer = requestLogService.getMemoryBuffer();
        AtomicInteger bufferSize = requestLogService.getBufferSize();
        if (buffer == null) {
            log.warn("LogAgent memory buffer not available");
            return;
        }

        int maxRecords = configService.getRequestLogMaxRecords();
        for (LogTask task : batch) {
            String matchChain = analyzeMatchChain(task, detailedAnalysis);
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
        return analyzeMatchChain(task, analysisEnabled);
    }

    private String analyzeMatchChain(LogTask task, boolean detailedAnalysis) {
        if (!detailedAnalysis) {
            return task.getMatchChain();
        }

        List<CandidateSnapshot> candidates = task.getCandidates();
        if (candidates == null || candidates.isEmpty() || task.getAnalysisBody() == null) {
            return task.getMatchChain();
        }

        try {
            ConditionMatcher.PreparedBody preparedBody =
                    conditionMatcher.prepareBody(task.getAnalysisBody(), task.getMatchOutcomes());
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
                        candidate.getHeaderCondition(), preparedBody,
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
    private RequestLog toEntity(LogTask task, boolean detailedAnalysis) {
        String matchChain = analyzeMatchChain(task, detailedAnalysis);
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
                .forwarded(task.isForwarded())
                .forwardTarget(task.getForwardTarget())
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
                .forwarded(task.isForwarded())
                .forwardTarget(task.getForwardTarget())
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
