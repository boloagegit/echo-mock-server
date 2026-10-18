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
@ConditionalOnProperty(name = "echo.request-log.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class LogAgent extends AbstractBatchAgent<LogTask> {

    private static final String AGENT_NAME = "log-agent";
    private static final long DURABLE_CLEANUP_INTERVAL = 1_000;
    /** 檢查待寫入量是否超過保留上限的間隔。 */
    private static final long BACKLOG_CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long SKIP_WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    // 以下只由 durable consumer 執行緒使用
    private long nextBacklogCheckNanos = System.nanoTime();
    private long skippedSinceWarning;
    /** 跳過時的保留上限（警告裡顯示；停止時不必再讀設定）。 */
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
    private final Semaphore durableWorkAvailable = new Semaphore(0);
    private ExecutorService durableConsumerExecutor;
    /** 不等記錄寫入就回應時的暫存區；嚴格模式（等寫入才回應）或 memory 模式為 null。 */
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

    /** 不等記錄寫入時暫存區的上限。 */
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

    /** {@code buffer} 為 null：等記錄寫入暫存檔才回應（嚴格模式）。 */
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
     * 接收一筆請求記錄。預設（{@code echo.request-log.wait-for-write=false}）放進暫存區後立即返回，
     * 永不失敗：寫入跟不上時丟掉最舊的記錄。{@code wait-for-write=true} 時等記錄寫入暫存檔才返回，
     * 無法寫入時拋出 {@link RequestLogUnavailableException}。Memory 模式維持原本的盡力而為。
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
                // 暫存區（等待寫入的記錄）與暫存檔的序列化資料都在記憶體中
                .inFlightBytes(durable.inFlightBytes() + (handoff == null ? 0 : handoff.bytes()))
                .inFlightByteLimit(durable.inFlightByteLimit() + (handoff == null ? 0 : handoff.maxBytes()))
                .waitingProducers(durable.waitingProducers())
                .backpressureActive(durable.backpressureActive())
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
     * 主資料庫只保留最新 max-records 筆。待寫入的紀錄超過這個數量時，較舊的那些寫入後也會立刻被保留上限刪掉，
     * 所以直接跳過：在同一個交易中推進進度（不寫入資料列），再從 spool 刪除並釋放空間。
     * 這樣請求量長時間超過寫入速度時，待寫入的量維持在 max-records 左右，不會塞滿 spool 而卡住請求；
     * 留下的仍是最新的紀錄。跳過的筆數計入 dropped 並記一筆警告。
     *
     * @return 跳過後的進度；沒有跳過時回傳原本的進度
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
     * 跳過的筆數寫進警告：持續過載時每秒都會跳過，最多每 30 秒彙總一次；第一次跳過立即記錄。
     * 累積的筆數在間隔到了時寫出（即使之後沒有再跳過），停止時（force）一律寫出，
     * 所以警告裡的筆數加總等於實際跳過的筆數。
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
     * 逐筆寫入一批紀錄：被資料庫拒絕的那筆只推進進度、不寫入（記一筆警告並計入 dropped），
     * 其他錯誤（例如資料庫無法連線）照常拋出，由外層等待後重試，不會跳過任何紀錄。
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

    /** 資料本身不被接受（長度、NUL、NOT NULL 等限制），重試也不會成功。 */
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
                    // 過載結束後不會再有新的跳過：累積的筆數也要在間隔到了時寫進記錄
                    reportSkipped(now, false);
                }

                List<RequestLogSpool.SpoolEntry> fetched =
                        durableSpool.readAfter(checkpoint, durableBatchSize + 1,
                                durableSpool.statusSnapshot().inFlightByteLimit());
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
                try {
                    durableBatchWriter.persist(durableSpool.getSpoolId(), lastSequence,
                            entities, configService.getRequestLogMaxRecords());
                } catch (RuntimeException e) {
                    if (!isRejectedData(e)) {
                        throw e;
                    }
                    // 資料本身被資料庫拒絕（不是連不上）：逐筆重寫，只跳過被拒絕的那筆，其餘照常寫入
                    persistOneByOne(batch, detailedAnalysis);
                }
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
            } catch (RequestLogSpool.UnrecoverableSpoolEntryException e) {
                // A corrupt or oversized head cannot be made safe by retrying:
                // keep it (and all following rows) durable and stop this
                // consumer until an operator repairs the retained row.
                log.error("Durable request-log consumer stopped at sequence {} ({}) {}; "
                                + "the spool row was retained for recovery",
                        e.sequence(), e.kind(), e.getMessage(), e);
                durableConsumerRunning.set(false);
                break;
            } catch (Exception | Error e) {
                storageRetryCount.incrementAndGet();
                // Error（例如 OutOfMemoryError）也不能讓這個唯一的寫入執行緒結束，否則從此不再有新的請求記錄。
                // 從資料庫重新讀取已確認的進度（逐筆重寫可能已前進一部分）；未寫入的資料留在暫存檔，稍後重試
                checkpoint = null;
                try {
                    log.warn("Durable request-log persistence paused; data remains in spool: {}",
                            e instanceof Error ? e.toString() : e.getMessage());
                } catch (Throwable ignored) {
                    // 記錄失敗也要繼續
                }
                try {
                    awaitDurableWork();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 停止時把還沒寫進記錄的跳過筆數補上，警告的總數才會等於 dropped 計數裡跳過的部分
        reportSkipped(System.nanoTime(), true);
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
     * 判斷候選規則是否有任何條件（body/query/header）。
     */
    private boolean hasAnyCondition(CandidateSnapshot candidate) {
        return isNotBlank(candidate.getMatchSpec())
                || isNotBlank(candidate.getBodyCondition())
                || isNotBlank(candidate.getQueryCondition())
                || isNotBlank(candidate.getHeaderCondition());
    }

    /**
     * 從 CandidateSnapshot 建構條件描述字串。
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
     * 將 LogTask 轉換為 RequestLogService.LogEntry（Memory 模式用）。
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
