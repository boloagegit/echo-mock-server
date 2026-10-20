package com.echo.agent;

import com.echo.core.match.MatchExplanation;
import com.echo.entity.Protocol;
import com.echo.entity.RequestLog;
import com.echo.repository.RequestLogRepository;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchChainEntry;
import com.echo.service.MatchExplanationJson;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Log Agent: writes request logs asynchronously in batches.
 * <p>
 * Supports two storage modes:
 * <ul>
 *   <li>Database mode: batch saveAll, then deleteOldest when max-records is exceeded.</li>
 *   <li>Memory mode: write to the RequestLogService memory ring buffer.</li>
 * </ul>
 * <p>
 * When {@code echo.agent.analysis.enabled=true}, analyze the matching chain in the background under normal load;
 * pause this optional analysis when the queue backs up to protect mock and forwarding traffic.
 */
@Component
@ConditionalOnProperty(name = "echo.request-log.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class LogAgent extends AbstractBatchAgent<LogTask> {

    private static final String AGENT_NAME = "log-agent";
    private static final long DURABLE_CLEANUP_INTERVAL = 1_000;
    /** Interval for checking whether pending records exceed the retention limit. */
    private static final long BACKLOG_CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long SKIP_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    // Accessed only by the durable consumer thread.
    private long nextBacklogCheckNanos = System.nanoTime();
    private long skippedSinceWarning;
    /** Retention limit used when skipping records, for warnings without rereading settings during shutdown. */
    private int skippedMaxRecords;
    private long lastSkipWarningNanos;

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
    private final AtomicLong storageRetryCount = new AtomicLong();
    private volatile long lastSuccessfulWriteEpochMillis;
    private volatile long stoppedAtSequence;
    private final Semaphore durableWorkAvailable = new Semaphore(0);
    private ExecutorService durableConsumerExecutor;
    /** Buffer for replies that do not wait for log writes; null in strict mode (wait for the write) or memory mode. */
    private final RequestLogHandoff handoff;

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
            RequestLogBatchWriter durableBatchWriter,
            @Value("${echo.request-log.wait-for-write:false}") boolean waitForWrite,
            @Value("${echo.request-log.buffer.max-entries:20000}") int bufferMaxEntries,
            @Value("${echo.request-log.buffer.max-mb:64}") int bufferMaxMb) {
        this(requestLogRepository, configService, conditionMatcher, requestLogService,
                durableSpoolProvider.getIfAvailable(), durableBatchWriter,
                queueCapacity, batchSize, flushIntervalSeconds,
                analysisEnabled, maxWriteRatePerSecond,
                waitForWrite ? null : new BufferLimits(bufferMaxEntries, (long) Math.max(1, bufferMaxMb) * 1024 * 1024));
    }

    /** Maximum buffer capacity when replies do not wait for log writes. */
    record BufferLimits(int maxEntries, long maxBytes) {
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
        this(requestLogRepository, configService, conditionMatcher, requestLogService,
                durableSpool, durableBatchWriter, queueCapacity, batchSize, flushIntervalSeconds,
                analysisEnabled, maxWriteRatePerSecond, null);
    }

    /** A null {@code buffer} selects strict mode: wait for the record to reach the spool before replying. */
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
            int maxWriteRatePerSecond,
            BufferLimits buffer) {
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
        this.handoff = buffer == null || durableSpool == null ? null
                : new RequestLogHandoff(buffer.maxEntries(), buffer.maxBytes(), this::writeToSpool,
                        250, System::nanoTime);
    }

    @PostConstruct
    public void init() {
        start();
        if (!configService.isRequestLogMemoryMode()) {
            if (handoff != null) {
                handoff.start();
            }
            startDurableConsumer();
        }
    }

    @PreDestroy
    public void destroy() {
        if (handoff != null) {
            handoff.stop(5_000);
        }
        stopDurableConsumer();
        shutdown();
    }

    private RequestLogSpool.AppendResult writeToSpool(List<LogTask> batch) {
        RequestLogSpool.AppendResult result = durableSpool.appendAll(batch);
        if (result.handled() > 0) {
            durableWorkAvailable.release();
        }
        return result;
    }

    /**
     * Accept a request log. By default ({@code echo.request-log.wait-for-write=false}), return immediately after buffering it.
     * This mode never fails: drop the oldest records if writes cannot keep up. {@code wait-for-write=true} waits for the spool write,
     * throwing {@link RequestLogUnavailableException} if it fails. Memory mode retains its existing best-effort behavior.
     */
    public void submitDurably(Supplier<? extends LogTask> taskSupplier) {
        Objects.requireNonNull(taskSupplier, "taskSupplier");
        if (handoff != null && !configService.isRequestLogMemoryMode()) {
            LogTask task;
            try {
                task = Objects.requireNonNull(taskSupplier.get(), "request log task");
            } catch (RuntimeException e) {
                log.debug("Cannot create request-log task: {}", e.toString());
                handoff.countDropped();
                return;
            }
            handoff.offer(task);
            return;
        }
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

    public boolean isDurableConsumerRunning() { return durableConsumerRunning.get(); }

    public boolean isDurableMode() {
        return durableSpool != null && !configService.isRequestLogMemoryMode();
    }

    public boolean isStorageUnavailable() {
        return durableSpool != null && durableSpool.isStorageUnavailable();
    }

    public Map<String, Object> diskReserveMetrics() {
        return durableSpool == null ? Map.of() : durableSpool.diskReserveMetrics();
    }

    @Override
    public AgentStats getStats() {
        AgentStats base = super.getStats();
        if (durableSpool == null || configService.isRequestLogMemoryMode()) {
            return base;
        }
        RequestLogSpool.StatusSnapshot durable = durableSpool.statusSnapshot();
        long buffered = handoff == null ? 0 : handoff.size();
        long overflow = handoff == null ? 0 : handoff.overflowDroppedCount();
        long unconfirmed = handoff == null ? 0 : handoff.writeNotConfirmedCount();
        long retention = base.getRetentionSkippedCount();
        long totalDropped = base.getDroppedCount() + (handoff == null ? 0 : handoff.droppedCount());
        return AgentStats.builder()
                .queueSize((int) Math.min(Integer.MAX_VALUE, durable.pendingItems() + buffered))
                .processedCount(base.getProcessedCount())
                .droppedCount(totalDropped)
                .retentionSkippedCount(retention)
                .bufferOverflowDroppedCount(overflow)
                .writeNotConfirmedCount(unconfirmed)
                .otherDroppedCount(base.getOtherDroppedCount() + (handoff == null ? 0 : handoff.otherDroppedCount()))
                .storageRetryCount(storageRetryCount.get() + durableSpool.getStorageRetryCount())
                .queueBytes(durable.pendingBytes())
                .queueCapacityBytes(durable.pendingByteLimit())
                // The pending-record buffer and the spool's serialized payloads both consume memory.
                .inFlightBytes(durable.inFlightBytes() + (handoff == null ? 0 : handoff.bytes()))
                .inFlightByteLimit(durable.inFlightByteLimit() + (handoff == null ? 0 : handoff.maxBytes()))
                .waitingProducers(durable.waitingProducers())
                .backpressureActive(durable.backpressureActive())
                .durableConsumerRunning(durableConsumerRunning.get())
                .lastSuccessfulWriteEpochMillis(lastSuccessfulWriteEpochMillis)
                .stoppedAtSequence(stoppedAtSequence)
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
     * Database mode: convert LogTask to RequestLog entity, saveAll + deleteOldest.
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
        stoppedAtSequence = 0;
        durableConsumerExecutor.execute(this::durableConsumeLoop);
    }

    private void stopDurableConsumer() {
        boolean wasRunning = durableConsumerRunning.getAndSet(false);
        if (!wasRunning && durableConsumerExecutor == null) {
            return;
        }
        durableWorkAvailable.release();
        ExecutorService executor = durableConsumerExecutor;
        durableConsumerExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The main database retains only the latest max-records entries. Older pending entries beyond this limit would be deleted immediately after insertion,
     * so skip them: advance the checkpoint in the same transaction without inserting rows, then delete them from the spool and release capacity.
     * If traffic exceeds write throughput for a prolonged period, this keeps the backlog near max-records instead of filling the spool and blocking requests.
     * The newest records are still retained. Skipped records count as dropped and produce a warning.
     *
     * @return The checkpoint after skipping, or the original checkpoint if nothing was skipped
     */
    private long skipBacklogBeyondRetention(long checkpoint) {
        int maxRecords = configService.getRequestLogMaxRecords();
        long cutoff = durableSpool.sequenceBeforeNewest(maxRecords);
        if (cutoff <= checkpoint) {
            return checkpoint;
        }
        long skipped = durableSpool.countBetween(checkpoint, cutoff);
        durableBatchWriter.persist(durableSpool.getSpoolId(), cutoff, List.of(), maxRecords);
        durableSpool.deleteThrough(cutoff);
        if (skipped > 0) {
            recordSkipped(skipped);
            skippedSinceWarning += skipped;
            skippedMaxRecords = maxRecords;
            reportSkipped(System.nanoTime(), false);
        }
        return cutoff;
    }

    /**
     * Report skipped records in warnings. Sustained overload may skip records every second, so aggregate warnings at most once every 30 seconds; report the first skip immediately.
     * Flush the accumulated count when the interval expires, even if no further records are skipped, and always flush on shutdown (force),
     * so warning counts sum to the actual number of skipped records.
     */
    private void reportSkipped(long now, boolean force) {
        if (skippedSinceWarning == 0) {
            return;
        }
        if (!force && lastSkipWarningNanos != 0 && now - lastSkipWarningNanos < SKIP_WARNING_INTERVAL_NANOS) {
            return;
        }
        log.warn("Request logs arrive faster than they can be saved; skipped {} older entries that the "
                + "{}-record limit would delete anyway", skippedSinceWarning, skippedMaxRecords);
        skippedSinceWarning = 0;
        lastSkipWarningNanos = now;
    }

    /**
     * Write a batch one record at a time: for a record rejected by the database, advance the checkpoint without inserting it, warn, and count it as dropped.
     * Propagate other failures, such as an unavailable database, so the outer loop waits and retries without skipping records.
     */
    private void persistOneByOne(List<RequestLogSpool.SpoolEntry> batch, boolean detailedAnalysis) {
        int maxRecords = configService.getRequestLogMaxRecords();
        for (RequestLogSpool.SpoolEntry entry : batch) {
            try {
                durableBatchWriter.persist(durableSpool.getSpoolId(), entry.sequence(),
                        List.of(toEntity(entry.task(), detailedAnalysis)), maxRecords);
            } catch (RuntimeException e) {
                if (!isRejectedData(e)) {
                    throw e;
                }
                durableBatchWriter.persist(durableSpool.getSpoolId(), entry.sequence(), List.of(), maxRecords);
                log.warn("Request log at spool sequence {} was rejected by the database and skipped: {}",
                        entry.sequence(), rootMessage(e));
                recordRejected("rejected by the database");
            }
        }
    }

    /** The data itself violates constraints such as length, NUL, or NOT NULL; retrying cannot succeed. */
    public static boolean isRejectedData(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DataIntegrityViolationException
                    || t instanceof org.hibernate.exception.DataException
                    || t instanceof org.hibernate.exception.ConstraintViolationException
                    || t instanceof java.sql.SQLDataException
                    || t instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
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
                long now = System.nanoTime();
                if (now - nextBacklogCheckNanos >= 0) {
                    nextBacklogCheckNanos = now + BACKLOG_CHECK_INTERVAL_NANOS;
                    long skippedThrough = skipBacklogBeyondRetention(checkpoint);
                    if (skippedThrough > checkpoint) {
                        checkpoint = skippedThrough;
                        lastCleanedCheckpoint = skippedThrough;
                    }
                    // Once overload ends, no new skips trigger warnings; flush the accumulated count when the interval expires anyway.
                    reportSkipped(now, false);
                }

                RequestLogSpool.ReadBatch fetchedBatch =
                        durableSpool.readBatchAfter(checkpoint, durableBatchSize + 1,
                                durableSpool.statusSnapshot().inFlightByteLimit());
                List<RequestLogSpool.SpoolEntry> fetched = fetchedBatch.entries();
                if (fetched.isEmpty()) {
                    if (checkpoint > lastCleanedCheckpoint) {
                        durableSpool.deleteThrough(checkpoint);
                        lastCleanedCheckpoint = checkpoint;
                    }
                    awaitDurableWork();
                    continue;
                }

                boolean countBacklog = fetched.size() > durableBatchSize;
                boolean hasBacklog = countBacklog || fetchedBatch.byteLimitReached();
                List<RequestLogSpool.SpoolEntry> batch = countBacklog
                        ? fetched.subList(0, durableBatchSize) : fetched;
                boolean detailedAnalysis = analysisEnabled && !hasBacklog;
                long startedAt = System.nanoTime();
                List<RequestLog> entities = batch.stream()
                        .map(entry -> toEntity(entry.task(), detailedAnalysis))
                        .toList();
                long lastSequence = batch.get(batch.size() - 1).sequence();
                try {
                    durableBatchWriter.persist(durableSpool.getSpoolId(), lastSequence,
                            entities, configService.getRequestLogMaxRecords());
                } catch (RuntimeException e) {
                    if (!isRejectedData(e)) {
                        throw e;
                    }
                    // The database rejected the data itself, rather than the connection: retry individually, skipping only rejected records and writing the rest normally.
                    persistOneByOne(batch, detailedAnalysis);
                }
                checkpoint = lastSequence;
                lastSuccessfulWriteEpochMillis = System.currentTimeMillis();
                if (checkpoint - lastCleanedCheckpoint >= DURABLE_CLEANUP_INTERVAL) {
                    durableSpool.deleteThrough(checkpoint);
                    lastCleanedCheckpoint = checkpoint;
                }
                recordProcessed(batch.size());
                afterBatchProcessed(batch.size(), System.nanoTime() - startedAt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RequestLogSpool.UnrecoverableSpoolEntryException e) {
                // A corrupt or oversized head cannot be made safe by retrying:
                // keep it (and all following rows) durable and stop this
                // consumer until an operator repairs the retained row.
                log.error("Durable request-log consumer stopped at sequence {} ({}) {}; "
                                + "the spool row was retained for recovery",
                        e.sequence(), e.kind(), e.getMessage(), e);
                stoppedAtSequence = e.sequence();
                durableConsumerRunning.set(false);
                break;
            } catch (Exception | Error e) {
                storageRetryCount.incrementAndGet();
                // Even Error (such as OutOfMemoryError) must not terminate the sole writer thread, which would prevent all future request logging.
                // Reload the confirmed checkpoint from the database, since individual retries may have advanced it; leave unwritten data in the spool for a later retry.
                checkpoint = null;
                try {
                    log.warn("Durable request-log persistence paused; data remains in spool: {}",
                            e instanceof Error ? e.toString() : e.getMessage());
                } catch (Throwable ignored) {
                    // Continue even if recording fails
                }
                try {
                    awaitDurableWork();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // On shutdown, flush any unreported skip count so warning totals match the skipped portion of the dropped counter.
        reportSkipped(System.nanoTime(), true);
    }

    private void awaitDurableWork() throws InterruptedException {
        if (durableWorkAvailable.tryAcquire(250, TimeUnit.MILLISECONDS)) {
            durableWorkAvailable.drainPermits();
        }
    }

    /**
     * Memory mode: write to the RequestLogService memory ring buffer.
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
     * Matching-chain analysis.
     * <p>
     * When {@code echo.agent.analysis.enabled=true}, traverse the candidate rules of LogTask,
     * Call {@code conditionMatcher.matchesPreparedWithDetail()} for each enabled rule to get structured results,
     * Determine match / shadowed / near-miss / mismatch / skipped / fallback,
     * Serialize the results as JSON and return them.
     *
     * @param task LogTask
     * @return Analyzed matchChain string
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
                MatchExplanation explanation = conditionMatcher.explainPrepared(
                        candidate.getMatchSpec(), candidate.getBodyCondition(), candidate.getQueryCondition(),
                        candidate.getHeaderCondition(), preparedBody,
                        task.getQueryString(), task.getHeaders());
                ConditionMatcher.ConditionDetail detail = ConditionMatcher.toConditionDetail(explanation);

                String detailStr = joinDetails(detail.getResults());

                boolean isMatchedRule = candidate.getRuleId().equals(matchedRuleId);

                if (detail.isOverallMatch()) {
                    if (isMatchedRule) {
                        chain.add(new MatchChainEntry(
                                candidate.getRuleId(), "match",
                                candidate.getEndpoint(), candidate.getDescription(),
                                buildConditionString(candidate),
                                detail.score(), detailStr, false, MatchExplanationJson.toJson(explanation)));
                    } else if (!hasShadowed) {
                        hasShadowed = true;
                        chain.add(new MatchChainEntry(
                                candidate.getRuleId(), "shadowed",
                                candidate.getEndpoint(), candidate.getDescription(),
                                buildConditionString(candidate),
                                detail.score(), detailStr, false, MatchExplanationJson.toJson(explanation)));
                    }
                } else if (detail.passedCount() > 0 && detail.passedCount() < detail.totalCount()) {
                    if (!hasNearMiss) {
                        hasNearMiss = true;
                        chain.add(new MatchChainEntry(
                                candidate.getRuleId(), "near-miss",
                                candidate.getEndpoint(), candidate.getDescription(),
                                buildConditionString(candidate),
                                detail.score(), detailStr, true, MatchExplanationJson.toJson(explanation)));
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
     * Determine whether the candidate rule has any conditions (body/query/header).
     */
    private boolean hasAnyCondition(CandidateSnapshot candidate) {
        return isNotBlank(candidate.getMatchSpec())
                || isNotBlank(candidate.getBodyCondition())
                || isNotBlank(candidate.getQueryCondition())
                || isNotBlank(candidate.getHeaderCondition());
    }

    /**
     * Construct condition description string from CandidateSnapshot.
     */
    private String buildConditionString(CandidateSnapshot candidate) {
        StringBuilder sb = new StringBuilder();
        appendCondition(sb, "match", candidate.getMatchSpec());
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
     * Join ConditionResult details with "; " to avoid the overhead of stream + Collectors.joining.
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
     * Convert LogTask to RequestLog entity (for Database mode).
     */
    private RequestLog toEntity(LogTask task, boolean detailedAnalysis) {
        String matchChain = analyzeMatchChain(task, detailedAnalysis);
        return RequestLog.builder()
                .diagnosticId(task.getDiagnosticId())
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
                .proxyError(RequestLog.limitProxyError(task.getProxyError()))
                .responseStatus(task.getResponseStatus())
                .requestBody(task.getRequestBody())
                .responseBody(task.getResponseBody())
                .faultType(task.getFaultType())
                .scenarioName(task.getScenarioName())
                .scenarioFromState(task.getScenarioFromState())
                .scenarioToState(task.getScenarioToState())
                .candidateCount(task.getCandidateCount())
                .matchedByFallback(task.getMatchedByFallback())
                .delayMs(task.getDelayMs())
                .sseEventCount(task.getSseEventCount())
                .scenarioSkipped(task.getScenarioSkipped())
                .build();
    }

    /**
     * Convert LogTask to RequestLogService.LogEntry (for Memory mode).
     */
    private RequestLogService.LogEntry toLogEntry(LogTask task, String matchChain) {
        return RequestLogService.LogEntry.builder()
                .diagnosticId(task.getDiagnosticId())
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
                .proxyError(RequestLog.limitProxyError(task.getProxyError()))
                .responseStatus(task.getResponseStatus())
                .requestBody(task.getRequestBody())
                .responseBody(task.getResponseBody())
                .faultType(task.getFaultType())
                .scenarioName(task.getScenarioName())
                .scenarioFromState(task.getScenarioFromState())
                .scenarioToState(task.getScenarioToState())
                .candidateCount(task.getCandidateCount())
                .matchedByFallback(task.getMatchedByFallback())
                .delayMs(task.getDelayMs())
                .sseEventCount(task.getSseEventCount())
                .scenarioSkipped(task.getScenarioSkipped())
                .build();
    }
}
