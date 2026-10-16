package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.service.RequestLogRejectedException;
import com.echo.service.RequestLogUnavailableException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Independent SQLite WAL used as the durable hand-off for request logs.
 *
 * <p>Producers wait only for a small group commit to this spool. The main SQLite
 * database is updated by {@link LogAgent}, so slow queries or a temporary main DB
 * outage cannot silently discard accepted request logs.</p>
 */
@Component
@ConditionalOnProperty(name = "echo.request-log.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "echo.request-log.store", havingValue = "database", matchIfMissing = true)
@Slf4j
public class RequestLogSpool {

    /** Maximum serialized candidate snapshot data retained in the hot cache. */
    static final long CANDIDATE_CACHE_MAX_BYTES = 16L * 1024 * 1024;
    /** Keep only a small bounded hand-off in heap; the rest of the spool is on disk. */
    static final long DEFAULT_IN_MEMORY_BYTES = 64L * 1024 * 1024;
    /** {@link #appendAll} waits at most this long for the commits of the tasks it admitted. */
    static final long APPEND_ALL_COMMIT_WAIT_MILLIS = 60_000;

    private static final String CREATE_SPOOL = """
            CREATE TABLE IF NOT EXISTS request_log_spool (
                sequence_id INTEGER PRIMARY KEY AUTOINCREMENT,
                payload BLOB NOT NULL,
                candidate_set_id TEXT,
                created_at INTEGER NOT NULL
            )
            """;
    private static final String CREATE_CANDIDATE_SETS = """
            CREATE TABLE IF NOT EXISTS candidate_snapshot_sets (
                candidate_set_id TEXT PRIMARY KEY,
                payload BLOB NOT NULL,
                created_at INTEGER NOT NULL
            )
            """;
    private static final String CREATE_METADATA = """
            CREATE TABLE IF NOT EXISTS spool_metadata (
                metadata_key TEXT PRIMARY KEY,
                metadata_value TEXT NOT NULL
            )
            """;
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    });

    private final ObjectMapper objectMapper;
    private final Path spoolPath;
    private final ArrayBlockingQueue<PendingAppend> appendQueue;
    private final int appendBatchSize;
    private final long groupCommitMillis;
    private final long queueOfferTimeoutMillis;
    private final long retryMillis;
    private final long maxPendingBytes;
    private final long maxInMemoryBytes;
    /**
     * {@code FULL} fsyncs every commit. When replies do not wait for their log
     * ({@code echo.request-log.wait-for-write=false}) {@code NORMAL} is enough: WAL
     * stays consistent after a crash and only a power or OS failure can lose the
     * latest commits, which that mode already accepts.
     */
    private final String synchronousMode;
    private final Cache<List<CandidateSnapshot>, CandidateSetPayload> candidateSetCache = Caffeine.newBuilder()
            .maximumWeight(CANDIDATE_CACHE_MAX_BYTES)
            .weigher((List<CandidateSnapshot> key, CandidateSetPayload payload) ->
                    candidateCacheWeight(payload))
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();
    /** One CAS-protected snapshot keeps byte and item counters mutually consistent. */
    private final AtomicReference<CapacityState> capacityState =
            new AtomicReference<>(CapacityState.empty());
    private final AtomicInteger waitingAppenders = new AtomicInteger();
    private final AtomicBoolean backpressureActive = new AtomicBoolean();
    /** Fail new producers quickly while the single writer is retrying storage. */
    private final AtomicBoolean storageUnavailable = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantLock capacityLock = new ReentrantLock(true);
    private final Condition capacityAvailable = capacityLock.newCondition();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock candidateSetLifecycleLock = new ReentrantReadWriteLock();

    private volatile String spoolId;
    /** 只由寫入執行緒使用：Error 的錯誤訊息最多每 30 秒一次。 */
    private long lastWriterErrorLogNanos;
    /** 測試用：每次提交前／提交後執行，用來模擬寫入時發生的錯誤。 */
    volatile CommitHook beforeCommitForTest = () -> { };
    volatile CommitHook afterCommitForTest = () -> { };
    /** 測試用：放棄一批並通知呼叫端之後執行，用來拉長通知與後續步驟之間的時間。 */
    volatile Runnable afterAbandonNotifiedForTest = () -> { };

    @FunctionalInterface
    interface CommitHook {
        void run() throws SQLException;
    }

    private static final class AbandonedWriteException extends RequestLogUnavailableException {
        private static final long serialVersionUID = 1L;

        AbandonedWriteException() {
            super("Request-log write was abandoned after an error and was not written", true);
        }
    }

    /**
     * 放棄的記錄共用同一個例外：記憶體不足時不必再配置。
     * 不記錄堆疊也不接受附加例外，所以共用不會累積任何狀態。
     */
    @SuppressWarnings("StaticAssignmentOfThrowable")
    private static final RequestLogUnavailableException WRITE_ABANDONED = new AbandonedWriteException();
    private ExecutorService writerExecutor;
    private Connection writerConnection;

    @Autowired
    public RequestLogSpool(
            ObjectMapper objectMapper,
            @Value("${echo.request-log.durable.spool-path:./data/request-log-spool.sqlite}") String spoolPath,
            @Value("${echo.request-log.durable.append-queue-capacity:2000}") int queueCapacity,
            @Value("${echo.request-log.durable.append-batch-size:100}") int appendBatchSize,
            @Value("${echo.request-log.durable.group-commit-ms:2}") long groupCommitMillis,
            @Value("${echo.request-log.durable.queue-offer-timeout-ms:10000}") long queueOfferTimeoutMillis,
            @Value("${echo.request-log.durable.retry-ms:250}") long retryMillis,
            @Value("${echo.request-log.durable.max-pending-bytes:10737418240}") long maxPendingBytes,
            @Value("${echo.request-log.wait-for-write:false}") boolean waitForWrite) {
        this(objectMapper, spoolPath, queueCapacity, appendBatchSize, groupCommitMillis,
                queueOfferTimeoutMillis, retryMillis, maxPendingBytes,
                DEFAULT_IN_MEMORY_BYTES, waitForWrite ? "FULL" : "NORMAL");
    }

    /** Package-private seam for tests: replies wait for their log ({@code synchronous=FULL}). */
    RequestLogSpool(
            ObjectMapper objectMapper,
            String spoolPath,
            int queueCapacity,
            int appendBatchSize,
            long groupCommitMillis,
            long queueOfferTimeoutMillis,
            long retryMillis,
            long maxPendingBytes) {
        this(objectMapper, spoolPath, queueCapacity, appendBatchSize, groupCommitMillis,
                queueOfferTimeoutMillis, retryMillis, maxPendingBytes, DEFAULT_IN_MEMORY_BYTES);
    }

    /** Package-private seam for tests that need a smaller heap hand-off budget. */
    RequestLogSpool(
            ObjectMapper objectMapper,
            String spoolPath,
            int queueCapacity,
            int appendBatchSize,
            long groupCommitMillis,
            long queueOfferTimeoutMillis,
            long retryMillis,
            long maxPendingBytes,
            long maxInMemoryBytes) {
        this(objectMapper, spoolPath, queueCapacity, appendBatchSize, groupCommitMillis,
                queueOfferTimeoutMillis, retryMillis, maxPendingBytes, maxInMemoryBytes, "FULL");
    }

    private RequestLogSpool(
            ObjectMapper objectMapper,
            String spoolPath,
            int queueCapacity,
            int appendBatchSize,
            long groupCommitMillis,
            long queueOfferTimeoutMillis,
            long retryMillis,
            long maxPendingBytes,
            long maxInMemoryBytes,
            String synchronousMode) {
        this.synchronousMode = synchronousMode;
        this.objectMapper = objectMapper;
        this.spoolPath = Path.of(spoolPath).toAbsolutePath().normalize();
        this.appendQueue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.appendBatchSize = Math.max(1, appendBatchSize);
        this.groupCommitMillis = Math.max(0, groupCommitMillis);
        this.queueOfferTimeoutMillis = Math.max(1, queueOfferTimeoutMillis);
        this.retryMillis = Math.max(10, retryMillis);
        this.maxPendingBytes = Math.max(1, maxPendingBytes);
        this.maxInMemoryBytes = Math.max(1, maxInMemoryBytes);
    }

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            initializeDatabase();
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        // A previous writer failure is a transient runtime state, not a
        // persisted spool state. A successful reopen must allow producers to
        // submit again after restart and recompute the current pressure state.
        storageUnavailable.set(false);
        updateBackpressureState();
        writerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "request-log-spool-writer");
            thread.setDaemon(true);
            return thread;
        });
        writerExecutor.execute(this::writerLoop);
        log.info("Request-log durable spool started: path={}, pendingBytes={}",
                spoolPath, capacityState.get().pendingBytes());
    }

    @PreDestroy
    public void stop() {
        // Flip the state before taking the lifecycle write lock. Producers may be
        // waiting for byte or queue capacity while holding the read lock; they
        // must be woken so shutdown cannot wait behind an indefinitely full queue.
        if (!running.compareAndSet(true, false)) {
            return;
        }
        signalCapacityAvailable();
        lifecycleLock.writeLock().lock();
        try {
            if (writerExecutor != null) {
                writerExecutor.shutdownNow();
                try {
                    writerExecutor.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            failPending(new RequestLogUnavailableException("Request-log spool is stopping"));
            candidateSetCache.invalidateAll();
            signalCapacityAvailable();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    public String getSpoolId() {
        return spoolId;
    }

    /**
     * Waits for a definitive durable-commit result after bounded admission.
     *
     * <p>The bounded queue and caller thread pools provide backpressure. Once
     * admitted, returning only after commit or a known failure avoids an
     * ambiguous timeout in which a caller could retry data that later commits.</p>
     */
    public void append(LogTask task) {
        await(admit(task));
    }

    /**
     * Admits several tasks and waits until the admitted ones are committed (a few
     * group commits instead of one per task). A task that can never be stored (too
     * large, not serializable) is rejected and skipped. Admission stops at the first
     * failure that a later retry may overcome (spool stopping, storage unavailable,
     * capacity wait timed out); the result says how many tasks, from the start of the
     * list, were handled, so the caller may retry the rest. Admitted tasks are never
     * reported for retry: re-sending them could duplicate the ones that did commit.
     * Their commits are awaited for at most {@link #APPEND_ALL_COMMIT_WAIT_MILLIS};
     * those not confirmed by then are counted as lost.
     */
    public AppendResult appendAll(List<? extends LogTask> tasks) {
        List<PendingAppend> admitted = new ArrayList<>(tasks.size());
        int handled = 0;
        int rejected = 0;
        for (LogTask task : tasks) {
            try {
                admitted.add(admit(task));
            } catch (RequestLogRejectedException e) {
                rejected++;
                log.debug("Request log rejected by the spool: {}", e.getMessage());
            } catch (RequestLogUnavailableException e) {
                break;
            } catch (RuntimeException e) {
                // Unexpected and specific to this task: retrying it would fail the same way
                rejected++;
                log.debug("Request log could not be admitted: {}", e.toString());
            }
            handled++;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(APPEND_ALL_COMMIT_WAIT_MILLIS);
        int failedCommits = 0;
        for (PendingAppend pending : admitted) {
            try {
                pending.committed().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException | CancellationException | TimeoutException e) {
                failedCommits++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedCommits++;
            }
        }
        if (failedCommits > 0) {
            log.debug("{} of {} request logs were not confirmed as committed", failedCommits, admitted.size());
        }
        return new AppendResult(handled, rejected + failedCommits);
    }

    /** {@code handled} tasks from the start of the list are done; {@code lost} of them were not stored. */
    public record AppendResult(int handled, int lost) {
    }

    private PendingAppend admit(LogTask task) {
        PendingAppend pending;
        long reservedPayloadBytes = 0;
        long serializationBytes = 0;
        boolean serializationReservationActive = false;
        long admissionDeadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(queueOfferTimeoutMillis);
        lifecycleLock.readLock().lock();
        try {
            if (!running.get()) {
                throw new RequestLogUnavailableException("Request-log spool is not running");
            }
            if (storageUnavailable.get()) {
                throw new RequestLogUnavailableException(
                        "Request-log storage is temporarily unavailable and recovering");
            }

            serializationBytes = estimateSerializationBytes(task);
            reserveSerialization(serializationBytes, admissionDeadlineNanos);
            serializationReservationActive = true;

            final byte[] payload;
            final CandidateSetPayload candidateSet;
            try {
                payload = objectMapper.writeValueAsBytes(SpoolTask.from(task));
                candidateSet = candidateSetFor(task);
            } catch (IOException e) {
                throw new RequestLogRejectedException("Cannot serialize request log", e);
            } catch (UncheckedIOException e) {
                throw new RequestLogRejectedException("Cannot serialize candidate snapshots", e.getCause());
            }
            long bytes = (long) payload.length
                    + (candidateSet != null ? candidateSet.payload().length : 0);
            reservePending(bytes, serializationBytes, admissionDeadlineNanos);
            reservedPayloadBytes = bytes;
            serializationReservationActive = false;

            pending = new PendingAppend(payload, candidateSet, bytes, new CompletableFuture<>());
            enqueue(pending, admissionDeadlineNanos);
        } catch (RequestLogUnavailableException e) {
            if (reservedPayloadBytes > 0) {
                releaseReservation(reservedPayloadBytes, 1, reservedPayloadBytes);
            } else if (serializationReservationActive) {
                releaseSerialization(serializationBytes);
            }
            throw e;
        } catch (InterruptedException e) {
            if (reservedPayloadBytes > 0) {
                releaseReservation(reservedPayloadBytes, 1, reservedPayloadBytes);
            } else if (serializationReservationActive) {
                releaseSerialization(serializationBytes);
            }
            Thread.currentThread().interrupt();
            throw new RequestLogUnavailableException("Interrupted while accepting request log", e);
        } finally {
            lifecycleLock.readLock().unlock();
        }
        return pending;
    }

    private static void await(PendingAppend pending) {
        try {
            pending.committed().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RequestLogUnavailableException unavailable) {
                throw unavailable;
            }
            throw new RequestLogUnavailableException(
                    "Cannot commit request log to durable spool", cause);
        }
    }

    private CandidateSetPayload candidateSetFor(LogTask task) {
        List<CandidateSnapshot> candidates = task.getCandidates();
        if (candidates.isEmpty()) {
            return null;
        }
        CandidateSetPayload cached = candidateSetCache.getIfPresent(candidates);
        if (cached != null) {
            return cached;
        }

        CandidateSetPayload serialized = serializeCandidateSet(candidates);
        // A single unusually large candidate list must not bypass the cache's
        // byte bound just because it is one entry. It is still persisted in the
        // durable spool, but is not retained as a hot heap object after append.
        if (candidateCacheWeight(serialized) <= CANDIDATE_CACHE_MAX_BYTES) {
            candidateSetCache.put(List.copyOf(candidates), serialized);
        }
        return serialized;
    }

    private CandidateSetPayload serializeCandidateSet(List<CandidateSnapshot> candidates) {
        try {
            List<CandidateRecord> records = candidates.stream().map(CandidateRecord::from).toList();
            byte[] payload = objectMapper.writeValueAsBytes(new CandidateSetRecord(records));
            MessageDigest digest = SHA_256.get();
            digest.reset();
            String id = HexFormat.of().formatHex(digest.digest(payload));
            return new CandidateSetPayload(id, payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Conservative pre-serialization estimate. Jackson has to allocate the
     * complete byte array before the exact size is known, so gating only after
     * serialization would let concurrent large requests defeat the heap bound.
     */
    private long estimateSerializationBytes(LogTask task) {
        long characters = 512;
        characters = addStringLength(characters, task.getRuleId());
        characters = addStringLength(characters, task.getDiagnosticId());
        characters = addStringLength(characters, task.getMethod());
        characters = addStringLength(characters, task.getEndpoint());
        characters = addStringLength(characters, task.getClientIp());
        characters = addStringLength(characters, task.getMatchChain());
        characters = addStringLength(characters, task.getTargetHost());
        characters = addStringLength(characters, task.getForwardTarget());
        characters = addStringLength(characters, task.getProxyError());
        characters = addStringLength(characters, task.getRequestBody());
        characters = addStringLength(characters, task.getResponseBody());
        characters = addStringLength(characters, task.getFaultType());
        characters = addStringLength(characters, task.getScenarioName());
        characters = addStringLength(characters, task.getScenarioFromState());
        characters = addStringLength(characters, task.getScenarioToState());
        characters = addStringLength(characters, task.getScenarioSkipped());
        characters = addStringLength(characters, task.getAnalysisBody());
        characters = addStringLength(characters, task.getQueryString());
        for (CandidateSnapshot candidate : task.getCandidates()) {
            characters = addStringLength(characters, candidate.getRuleId());
            characters = addStringLength(characters, candidate.getEndpoint());
            characters = addStringLength(characters, candidate.getDescription());
            characters = addStringLength(characters, candidate.getBodyCondition());
            characters = addStringLength(characters, candidate.getQueryCondition());
            characters = addStringLength(characters, candidate.getHeaderCondition());
            characters = addStringLength(characters, candidate.getMatchSpec());
            characters = saturatingAdd(characters, 32);
        }
        for (Map.Entry<String, String> header : task.getHeaders().entrySet()) {
            characters = addStringLength(characters, header.getKey());
            characters = addStringLength(characters, header.getValue());
        }
        characters = saturatingAdd(characters, (long) task.getMatchOutcomes().size() * 64);
        long estimate = saturatingMultiply(characters, 8);
        if (estimate > maxInMemoryBytes) {
            throw new RequestLogRejectedException(
                    "Request-log payload exceeds the configured in-memory serialization limit");
        }
        return Math.max(1, estimate);
    }

    private static long addStringLength(long total, String value) {
        return value == null ? total : saturatingAdd(total, value.length());
    }

    private static long saturatingMultiply(long value, long multiplier) {
        if (value <= 0 || multiplier <= 0) {
            return 0;
        }
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static int candidateCacheWeight(CandidateSetPayload payload) {
        // Account for both the serialized value and the retained immutable
        // CandidateSnapshot key. Three times the JSON size is deliberately
        // conservative while avoiding a full content hash on every request.
        long weight = saturatingAdd(saturatingMultiply(payload.payload().length, 3), 64);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, weight));
    }

    public List<SpoolEntry> readAfter(long sequence, int limit) {
        return readAfter(sequence, limit, Long.MAX_VALUE);
    }

    /**
     * Reads an ordered batch without materializing an unbounded amount of
     * serialized request-log data in the persistence worker. Metadata is read
     * first, so an oversized head row is rejected before either BLOB is loaded.
     * A later row that would exceed the remaining budget is left for the next
     * read. An oversized/corrupt head is reported as terminal and remains in
     * the spool for operator recovery; it is never silently deleted.
     */
    public List<SpoolEntry> readAfter(long sequence, int limit, long maxBytes) {
        String sql = "SELECT spool.sequence_id, spool.candidate_set_id, "
                + "length(spool.payload), length(candidates.payload) "
                + "FROM request_log_spool spool "
                + "LEFT JOIN candidate_snapshot_sets candidates "
                + "ON candidates.candidate_set_id = spool.candidate_set_id "
                + "WHERE spool.sequence_id > ? ORDER BY spool.sequence_id LIMIT ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sequence);
            statement.setInt(2, Math.max(1, limit));
            List<SpoolEntry> result = new ArrayList<>();
            Map<String, List<CandidateRecord>> candidateCache = new LinkedHashMap<>();
            long byteBudget = maxBytes <= 0 ? 1 : maxBytes;
            long loadedBytes = 0;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long sequenceId = rows.getLong(1);
                    String candidateSetId = rows.getString(2);
                    long payloadBytes = rows.getLong(3);
                    if (rows.wasNull() || payloadBytes < 0) {
                        throw unrecoverable(sequenceId,
                                UnrecoverableSpoolEntryException.Kind.CORRUPT,
                                0, byteBudget,
                                "Missing or invalid request-log payload length", null);
                    }
                    long candidateBytes = 0;
                    if (candidateSetId != null) {
                        candidateBytes = rows.getLong(4);
                        if (rows.wasNull() || candidateBytes < 0) {
                            throw unrecoverable(sequenceId,
                                    UnrecoverableSpoolEntryException.Kind.CORRUPT,
                                    payloadBytes, byteBudget,
                                    "Missing candidate snapshot set: " + candidateSetId, null);
                        }
                    }
                    long rowBytes = saturatingAdd(payloadBytes, candidateBytes);
                    long remainingBytes = byteBudget - loadedBytes;
                    if (rowBytes > remainingBytes) {
                        if (result.isEmpty()) {
                            throw unrecoverable(sequenceId,
                                    UnrecoverableSpoolEntryException.Kind.OVERSIZED,
                                    rowBytes, byteBudget,
                                    "Request-log spool head row exceeds the read byte limit", null);
                        }
                        break;
                    }
                    if (payloadBytes > Integer.MAX_VALUE) {
                        throw unrecoverable(sequenceId,
                                UnrecoverableSpoolEntryException.Kind.OVERSIZED,
                                rowBytes, byteBudget,
                                "Request-log spool payload cannot be represented in memory", null);
                    }
                    try {
                        byte[] payload = readPayload(connection, sequenceId);
                        if (payload.length != payloadBytes) {
                            throw new CandidateSnapshotCorruptionException(
                                    "Request-log payload length changed for sequence " + sequenceId);
                        }
                        SpoolTask stored = objectMapper.readValue(payload, SpoolTask.class);
                        List<CandidateRecord> candidates = candidateSetId == null ? null
                                : loadCandidateSet(connection, candidateSetId, candidateCache);
                        result.add(new SpoolEntry(sequenceId, stored.toLogTask(candidates), payload.length));
                        loadedBytes = saturatingAdd(loadedBytes, rowBytes);
                    } catch (CandidateSnapshotCorruptionException | IOException e) {
                        throw unrecoverable(sequenceId,
                                UnrecoverableSpoolEntryException.Kind.CORRUPT,
                                rowBytes, byteBudget,
                                "Cannot decode request-log spool head row", e);
                    }
                }
            }
            return result;
        } catch (UnrecoverableSpoolEntryException e) {
            throw e;
        } catch (SQLException e) {
            throw new RequestLogUnavailableException("Cannot read request-log spool", e);
        }
    }

    private byte[] readPayload(Connection connection, long sequenceId) throws SQLException, IOException {
        try (PreparedStatement payloadQuery = connection.prepareStatement(
                "SELECT payload FROM request_log_spool WHERE sequence_id = ?")) {
            payloadQuery.setLong(1, sequenceId);
            try (ResultSet payloadRow = payloadQuery.executeQuery()) {
                if (!payloadRow.next()) {
                    throw new IOException("Missing request-log spool row: " + sequenceId);
                }
                byte[] payload = payloadRow.getBytes(1);
                if (payload == null) {
                    throw new IOException("Null request-log spool payload: " + sequenceId);
                }
                return payload;
            }
        }
    }

    private static UnrecoverableSpoolEntryException unrecoverable(
            long sequenceId,
            UnrecoverableSpoolEntryException.Kind kind,
            long rowBytes,
            long byteLimit,
            String detail,
            Throwable cause) {
        return new UnrecoverableSpoolEntryException(sequenceId, kind, rowBytes, byteLimit, detail, cause);
    }

    private List<CandidateRecord> loadCandidateSet(
            Connection connection, String candidateSetId,
            Map<String, List<CandidateRecord>> candidateCache) throws SQLException, IOException {
        List<CandidateRecord> cached = candidateCache.get(candidateSetId);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT payload FROM candidate_snapshot_sets WHERE candidate_set_id = ?")) {
            query.setString(1, candidateSetId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new IOException("Missing candidate snapshot set: " + candidateSetId);
                }
                CandidateSetRecord stored = objectMapper.readValue(row.getBytes(1), CandidateSetRecord.class);
                List<CandidateRecord> candidates = stored.candidates() == null
                        ? List.of() : List.copyOf(stored.candidates());
                candidateCache.put(candidateSetId, candidates);
                return candidates;
            }
        }
    }

    /**
     * 保留最新 {@code keep} 筆時可以略過的最後一個序號：由新到舊數第 {@code keep + 1} 筆的序號；
     * 筆數不超過 {@code keep} 時回傳 0。
     */
    public long sequenceBeforeNewest(int keep) {
        String sql = "SELECT sequence_id FROM request_log_spool ORDER BY sequence_id DESC LIMIT 1 OFFSET ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(0, keep));
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RequestLogUnavailableException(
                    "Cannot inspect request-log spool: " + e.getMessage(), e);
        }
    }

    /** 序號在 {@code afterExclusive}（不含）到 {@code throughInclusive}（含）之間的筆數。 */
    public long countBetween(long afterExclusive, long throughInclusive) {
        String sql = "SELECT COUNT(*) FROM request_log_spool WHERE sequence_id > ? AND sequence_id <= ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, afterExclusive);
            statement.setLong(2, throughInclusive);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RequestLogUnavailableException(
                    "Cannot inspect request-log spool: " + e.getMessage(), e);
        }
    }

    public void deleteThrough(long sequence) {
        if (sequence <= 0) {
            return;
        }
        String sizeSql = "SELECT COALESCE(SUM(length(payload)), 0) FROM request_log_spool "
                + "WHERE sequence_id <= ?";
        String deleteSql = "DELETE FROM request_log_spool WHERE sequence_id <= ?";
        try (Connection connection = openConnection()) {
            long released = 0;
            // Keep the size read in its own autocommit transaction. Upgrading a WAL
            // read snapshot to a writer after a concurrent append can fail immediately
            // with SQLITE_BUSY_SNAPSHOT; appends always have higher sequence IDs, so a
            // separate delete transaction is both safe and contention-friendly here.
            try (PreparedStatement size = connection.prepareStatement(sizeSql)) {
                size.setLong(1, sequence);
                try (ResultSet row = size.executeQuery()) {
                    if (row.next()) {
                        released = row.getLong(1);
                    }
                }
            }
            int deletedRows;
            try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                delete.setLong(1, sequence);
                deletedRows = delete.executeUpdate();
            }
            releaseReservation(released, deletedRows, 0);
            cleanupUnreferencedCandidateSets();
        } catch (SQLException e) {
            throw new RequestLogUnavailableException(
                    "Cannot clean request-log spool: " + e.getMessage(), e);
        }
    }

    private void cleanupUnreferencedCandidateSets() throws SQLException {
        candidateSetLifecycleLock.writeLock().lock();
        try {
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                long releasedCount = 0;
                long releasedBytes = 0;
                try (ResultSet row = statement.executeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(length(payload)), 0) "
                                + "FROM candidate_snapshot_sets "
                                + "WHERE NOT EXISTS (SELECT 1 FROM request_log_spool "
                                + "WHERE request_log_spool.candidate_set_id "
                                + "= candidate_snapshot_sets.candidate_set_id)")) {
                    if (row.next()) {
                        releasedCount = row.getLong(1);
                        releasedBytes = row.getLong(2);
                    }
                }
                if (releasedCount == 0) {
                    return;
                }
                statement.executeUpdate("DELETE FROM candidate_snapshot_sets WHERE NOT EXISTS "
                        + "(SELECT 1 FROM request_log_spool "
                        + "WHERE request_log_spool.candidate_set_id "
                        + "= candidate_snapshot_sets.candidate_set_id)");
                releaseReservation(releasedBytes, 0, 0);
            }
        } finally {
            candidateSetLifecycleLock.writeLock().unlock();
        }
    }

    long pendingBytes() {
        return capacityState.get().pendingBytes();
    }

    long pendingItems() {
        return capacityState.get().pendingItems();
    }

    long candidateCacheEntries() {
        candidateSetCache.cleanUp();
        return candidateSetCache.estimatedSize();
    }

    /** Stable, read-only metrics used by the admin agent status endpoint. */
    public StatusSnapshot statusSnapshot() {
        CapacityState state = capacityState.get();
        return new StatusSnapshot(
                state.pendingBytes(), maxPendingBytes,
                state.inMemoryBytes(), maxInMemoryBytes,
                waitingAppenders.get(), backpressureActive.get(),
                state.pendingItems());
    }

    public boolean isStorageUnavailable() { return storageUnavailable.get(); }

    public record StatusSnapshot(
            long pendingBytes,
            long pendingByteLimit,
            long inFlightBytes,
            long inFlightByteLimit,
            int waitingProducers,
            boolean backpressureActive,
            long pendingItems) {
    }

    private record CapacityState(
            long pendingBytes, long inMemoryBytes, long pendingItems, long serializationBytes) {

        private static CapacityState empty() {
            return new CapacityState(0, 0, 0, 0);
        }
    }

    private void initializeDatabase() {
        try {
            Path parent = spoolPath.getParent();
            // createDirectories treats a symlink-to-directory (for example macOS /tmp)
            // as an existing non-directory on some JDK/file-system combinations.
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=" + synchronousMode);
                statement.execute(CREATE_SPOOL);
                ensureColumnExists(connection, "request_log_spool", "candidate_set_id", "TEXT");
                statement.execute(CREATE_CANDIDATE_SETS);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_spool_candidate_set "
                        + "ON request_log_spool(candidate_set_id)");
                statement.execute(CREATE_METADATA);
                statement.execute("DELETE FROM candidate_snapshot_sets WHERE NOT EXISTS "
                        + "(SELECT 1 FROM request_log_spool "
                        + "WHERE request_log_spool.candidate_set_id "
                        + "= candidate_snapshot_sets.candidate_set_id)");
                spoolId = loadOrCreateSpoolId(connection);
                try (ResultSet row = statement.executeQuery(
                        "SELECT COUNT(*), "
                                + "COALESCE(SUM(length(payload)), 0) "
                                + "+ COALESCE((SELECT SUM(length(payload)) FROM candidate_snapshot_sets), 0) "
                                + "FROM request_log_spool")) {
                    if (row.next()) {
                        capacityState.set(new CapacityState(
                                row.getLong(2), 0, row.getLong(1), 0));
                    }
                }
                updateBackpressureState();
            }
        } catch (IOException | SQLException e) {
            throw new RequestLogUnavailableException("Cannot initialize request-log spool", e);
        }
    }

    private void ensureColumnExists(
            Connection connection, String table, String column, String definition) throws SQLException {
        boolean exists = false;
        try (Statement schema = connection.createStatement();
             ResultSet columns = schema.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement migration = connection.createStatement()) {
                migration.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private String loadOrCreateSpoolId(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT metadata_value FROM spool_metadata WHERE metadata_key = 'spool_id'");
             ResultSet row = query.executeQuery()) {
            if (row.next()) {
                return row.getString(1);
            }
        }
        String id = UUID.randomUUID().toString();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO spool_metadata(metadata_key, metadata_value) VALUES ('spool_id', ?)")) {
            insert.setString(1, id);
            insert.executeUpdate();
        }
        return id;
    }

    private void reserveSerialization(long bytes, long deadlineNanos) throws InterruptedException {
        if (bytes <= 0 || bytes > maxInMemoryBytes) {
            throw new RequestLogRejectedException(
                    "Request-log payload exceeds the configured in-memory serialization limit");
        }

        // Normal traffic has room in the bounded hand-off. Keep it entirely
        // lock-free; only a contended/full hand-off takes the fair wait lock.
        if (waitingAppenders.get() == 0 && running.get()
                && tryReserveSerialization(bytes)) {
            return;
        }

        capacityLock.lockInterruptibly();
        try {
            waitingAppenders.incrementAndGet();
            try {
                while (running.get()) {
                    if (tryReserveSerialization(bytes)) {
                        updateBackpressureState();
                        return;
                    }
                    updateBackpressureState();
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        throw new RequestLogUnavailableException(
                                "Timed out waiting for request-log serialization capacity");
                    }
                    long waitResult = capacityAvailable.awaitNanos(remaining);
                    if (waitResult <= 0 && System.nanoTime() >= deadlineNanos) {
                        throw new RequestLogUnavailableException(
                                "Timed out waiting for request-log serialization capacity");
                    }
                }
                throw new RequestLogUnavailableException(
                        "Request-log spool is stopping before serializing the task");
            } finally {
                waitingAppenders.decrementAndGet();
                updateBackpressureState();
            }
        } finally {
            capacityLock.unlock();
        }
    }

    private void reservePending(
            long bytes, long serializationBytes, long deadlineNanos) throws InterruptedException {
        if (bytes <= 0) {
            throw new RequestLogRejectedException("Request-log payload must not be empty");
        }
        if (bytes > maxPendingBytes || bytes > maxInMemoryBytes) {
            throw new RequestLogRejectedException(
                    "Request-log payload exceeds the configured durable or in-memory byte limit");
        }

        // This CAS updates durable pending bytes, heap hand-off bytes, and the
        // serialization reservation as one snapshot. That prevents a producer
        // racing another producer from oversubscribing either limit.
        if (waitingAppenders.get() == 0 && running.get()
                && tryReservePending(bytes, serializationBytes)) {
            return;
        }

        capacityLock.lockInterruptibly();
        try {
            waitingAppenders.incrementAndGet();
            try {
                while (running.get()) {
                    if (tryReservePending(bytes, serializationBytes)) {
                        updateBackpressureState();
                        return;
                    }
                    updateBackpressureState();
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        throw new RequestLogUnavailableException(
                                "Timed out waiting for request-log durable capacity");
                    }
                    long waitResult = capacityAvailable.awaitNanos(remaining);
                    if (waitResult <= 0 && System.nanoTime() >= deadlineNanos) {
                        throw new RequestLogUnavailableException(
                                "Timed out waiting for request-log durable capacity");
                    }
                }
                throw new RequestLogUnavailableException(
                        "Request-log spool is stopping before accepting the task");
            } finally {
                waitingAppenders.decrementAndGet();
                updateBackpressureState();
            }
        } finally {
            capacityLock.unlock();
        }
    }

    private boolean tryReserveSerialization(long bytes) {
        while (true) {
            CapacityState current = capacityState.get();
            if (bytes > maxInMemoryBytes - current.inMemoryBytes()) {
                return false;
            }
            CapacityState next = new CapacityState(
                    current.pendingBytes(),
                    current.inMemoryBytes() + bytes,
                    current.pendingItems(),
                    current.serializationBytes() + bytes);
            if (capacityState.compareAndSet(current, next)) {
                updateBackpressureState();
                return true;
            }
        }
    }

    private boolean tryReservePending(long bytes, long serializationBytes) {
        while (true) {
            CapacityState current = capacityState.get();
            long availablePending = maxPendingBytes - current.pendingBytes();
            long availableMemory = maxInMemoryBytes - current.inMemoryBytes();
            if (current.serializationBytes() < serializationBytes
                    || bytes > availablePending
                    || bytes > saturatingAdd(availableMemory, serializationBytes)) {
                return false;
            }
            CapacityState next = new CapacityState(
                    current.pendingBytes() + bytes,
                    current.inMemoryBytes() - serializationBytes + bytes,
                    saturatingAdd(current.pendingItems(), 1),
                    current.serializationBytes() - serializationBytes);
            if (capacityState.compareAndSet(current, next)) {
                updateBackpressureState();
                return true;
            }
        }
    }

    private void releaseSerialization(long bytes) {
        if (bytes <= 0) {
            return;
        }
        while (true) {
            CapacityState current = capacityState.get();
            CapacityState next = new CapacityState(
                    current.pendingBytes(),
                    subtractAtMost(current.inMemoryBytes(), bytes),
                    current.pendingItems(),
                    subtractAtMost(current.serializationBytes(), bytes));
            if (capacityState.compareAndSet(current, next)) {
                break;
            }
        }
        afterRelease();
    }

    private void afterRelease() {
        updateBackpressureState();
        if (waitingAppenders.get() > 0) {
            signalCapacityAvailable();
        }
    }

    /** 記憶體不足時通知可以晚一點：等待中的寫入每 250 ms 會自己再檢查一次。 */
    private void afterReleaseQuietly() {
        try {
            afterRelease();
        } catch (Error ignored) {
            // 下一次容量變動時會再更新
        }
    }

    /** 釋放這筆保留的容量一次：釋放生效之後才算數，發生 Error 時可以重試。 */
    private boolean releaseOnce(PendingAppend pending, long pendingBytes, long itemCount, long inFlightBytes) {
        if (!pending.claimRelease()) {
            return false;
        }
        try {
            applyRelease(pendingBytes, itemCount, inFlightBytes);
            return true;
        } catch (Error e) {
            pending.unclaimRelease();
            throw e;
        }
    }

    /**
     * A full append queue is a normal backpressure state, not a request-loss
     * condition. Retry in short interruptible slices so shutdown remains
     * responsive while producers wait for the writer to drain the queue.
     */
    private void enqueue(PendingAppend pending, long deadlineNanos) throws InterruptedException {
        if (!running.get()) {
            throw new RequestLogUnavailableException(
                    "Request-log spool is stopping before queueing the task");
        }
        if (storageUnavailable.get()) {
            throw new RequestLogUnavailableException(
                    "Request-log storage is temporarily unavailable and recovering");
        }
        // Avoid taking the wait lock on the uncontended fast path. A failed
        // offer is the point at which this producer becomes a queue waiter and
        // must be reflected in the externally visible pressure metrics.
        if (appendQueue.offer(pending)) {
            return;
        }

        waitingAppenders.incrementAndGet();
        updateBackpressureState();
        try {
            while (running.get()) {
                if (storageUnavailable.get()) {
                    throw new RequestLogUnavailableException(
                            "Request-log storage is temporarily unavailable and recovering");
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new RequestLogUnavailableException(
                            "Timed out waiting for request-log append capacity");
                }
                long waitNanos = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250));
                if (appendQueue.offer(pending, waitNanos, TimeUnit.NANOSECONDS)) {
                    return;
                }
            }
            throw new RequestLogUnavailableException(
                    "Request-log spool is stopping before queueing the task");
        } finally {
            waitingAppenders.decrementAndGet();
            updateBackpressureState();
        }
    }

    private void releaseReservation(long pendingBytes, long itemCount, long inFlightBytes) {
        if (pendingBytes <= 0 && itemCount <= 0 && inFlightBytes <= 0) {
            return;
        }
        applyRelease(pendingBytes, itemCount, inFlightBytes);
        afterRelease();
    }

    /**
     * 只做容量的扣減：比較並交換成功後立即返回，所以發生 Error 時一定還沒生效，可以安全重試。
     */
    private void applyRelease(long pendingBytes, long itemCount, long inFlightBytes) {
        if (pendingBytes <= 0 && itemCount <= 0 && inFlightBytes <= 0) {
            return;
        }
        while (true) {
            CapacityState current = capacityState.get();
            CapacityState next = new CapacityState(
                    subtractAtMost(current.pendingBytes(), pendingBytes),
                    subtractAtMost(current.inMemoryBytes(), inFlightBytes),
                    subtractAtMost(current.pendingItems(), itemCount),
                    current.serializationBytes());
            if (capacityState.compareAndSet(current, next)) {
                return;
            }
        }
    }

    private void signalCapacityAvailable() {
        capacityLock.lock();
        try {
            updateBackpressureState();
            capacityAvailable.signalAll();
        } finally {
            capacityLock.unlock();
        }
    }

    private void updateBackpressureState() {
        long highWatermark = Math.max(1, maxPendingBytes - maxPendingBytes / 5);
        long lowWatermark = maxPendingBytes / 2;
        CapacityState state = capacityState.get();
        long pending = state.pendingBytes();
        long inMemory = state.inMemoryBytes();
        boolean currentlyActive = backpressureActive.get();
        if (!currentlyActive && (storageUnavailable.get() || waitingAppenders.get() > 0
                || pending >= highWatermark || inMemory >= maxInMemoryBytes)) {
            backpressureActive.set(true);
        } else if (currentlyActive && !storageUnavailable.get() && waitingAppenders.get() == 0
                && pending <= lowWatermark && inMemory < maxInMemoryBytes) {
            backpressureActive.set(false);
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long subtractAtMost(long value, long amount) {
        if (amount <= 0) {
            return value;
        }
        return Math.max(0, value - amount);
    }

    private void writerLoop() {
        List<PendingAppend> batch = new ArrayList<>(appendBatchSize);
        try {
            while (running.get() || !appendQueue.isEmpty()) {
                try {
                    PendingAppend first = appendQueue.poll(250, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    batch.add(first);
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(groupCommitMillis);
                    while (batch.size() < appendBatchSize) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            break;
                        }
                        PendingAppend next = appendQueue.poll(remaining, TimeUnit.NANOSECONDS);
                        if (next == null) {
                            break;
                        }
                        batch.add(next);
                    }
                    commitWithRetry(batch);
                    batch.clear();
                } catch (InterruptedException e) {
                    if (running.get()) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Error e) {
                    // 例如 OutOfMemoryError（收集這批時發生，尚未寫入）：這是唯一的寫入執行緒，
                    // 結束就再也沒有記錄寫得進來，等待中的呼叫端也會一直等。放棄這批，稍後繼續。
                    abandonUncommitted(batch, e);
                    batch.clear();
                    if (!pauseAfterWriterError()) {
                        break;
                    }
                }
            }
        } finally {
            closeWriterConnection();
        }
        if (!batch.isEmpty()) {
            failBatch(batch, new RequestLogUnavailableException("Request-log spool is stopping"));
        }
    }

    /**
     * 放棄一批確定沒有寫入的記錄（發生 Error 時）：換一條新連線，通知呼叫端失敗並釋放保留的容量，
     * 並清掉「暫時無法寫入」的狀態，讓下一筆記錄重新試探暫存檔（否則所有新記錄都會被拒絕）。
     * 每筆的結算可以重複執行，記憶體仍不足時重試到完成為止。
     */
    private void abandonUncommitted(List<PendingAppend> batch, Error error) {
        try {
            rollbackWriterConnection();
        } catch (Throwable ignored) {
            // 關閉連線時一併丟棄未提交的資料
        }
        // 一定要丟掉這條連線：沿用的話，這批未提交的資料可能跟著下一批一起提交
        Connection abandoned = writerConnection;
        writerConnection = null;
        try {
            if (abandoned != null) {
                abandoned.close();
            }
        } catch (Throwable ignored) {
            // 已經不再使用
        }
        // 先清狀態再通知：呼叫端一收到失敗就可能馬上送下一筆，那時狀態必須已經清掉
        storageUnavailable.set(false);
        failUntilDone(batch);
        afterAbandonNotifiedForTest.run();
        try {
            updateBackpressureState();
        } catch (Throwable ignored) {
            // 下一次容量變動時會再更新
        }
        logWriterError(error, batch.size());
    }

    /**
     * 已寫入的一批：一定回報成功。每一步都可以重複執行（容量的釋放生效之後才標記），
     * 記憶體仍不足時重試到完成為止；不配置額外的物件，避免已寫入的一批被當成未寫入。
     */
    private void settleCommittedUntilDone(List<PendingAppend> batch, Set<String> insertedCandidateSets,
                                          java.util.concurrent.atomic.AtomicBoolean duplicatesReleased) {
        while (true) {
            try {
                settleCommitted(batch, insertedCandidateSets, duplicatesReleased);
                return;
            } catch (Error e) {
                pauseQuietly();
            }
        }
    }

    private void settleCommitted(List<PendingAppend> batch, Set<String> insertedCandidateSets,
                                 java.util.concurrent.atomic.AtomicBoolean duplicatesReleased) {
        if (!duplicatesReleased.get()) {
            applyRelease(duplicateCandidateBytes(batch, insertedCandidateSets), 0, 0);
            duplicatesReleased.set(true);
        }
        for (PendingAppend pending : batch) {
            pending.committed().complete(null);
            releaseOnce(pending, 0, 0, pending.reservedBytes());
        }
        afterReleaseQuietly();
    }

    /** 放棄的一批：通知失敗並釋放容量，重試到完成為止。 */
    private void failUntilDone(List<PendingAppend> batch) {
        while (true) {
            try {
                failBatch(batch, WRITE_ABANDONED);
                return;
            } catch (Error e) {
                pauseQuietly();
            }
        }
    }

    private void pauseQuietly() {
        try {
            TimeUnit.MILLISECONDS.sleep(retryMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void logWriterError(Error error, int entries) {
        long now = System.nanoTime();
        if (lastWriterErrorLogNanos != 0 && now - lastWriterErrorLogNanos < TimeUnit.SECONDS.toNanos(30)) {
            return;
        }
        lastWriterErrorLogNanos = now;
        try {
            log.error("Request-log spool writer hit {}; {} entries were not written; the writer keeps running",
                    error, entries);
        } catch (Throwable ignored) {
            // 記錄失敗也不能讓寫入執行緒結束
        }
    }

    /** 發生 Error 後稍等再繼續；停止中或被中斷時回傳 false。 */
    private boolean pauseAfterWriterError() {
        try {
            TimeUnit.MILLISECONDS.sleep(retryMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void commitWithRetry(List<PendingAppend> batch) throws InterruptedException {
        while (running.get()) {
            boolean committed = false;
            Set<String> insertedCandidateSets = Set.of();
            java.util.concurrent.atomic.AtomicBoolean duplicatesReleased = new java.util.concurrent.atomic.AtomicBoolean();
            candidateSetLifecycleLock.readLock().lock();
            try {
                beforeCommitForTest.run();
                Connection connection = getWriterConnection();
                Map<String, CandidateSetPayload> candidateSets = uniqueCandidateSets(batch);
                insertedCandidateSets = persistCandidateSets(connection, candidateSets);
                try (PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO request_log_spool(payload, candidate_set_id, created_at) VALUES (?, ?, ?)")) {
                    for (PendingAppend pending : batch) {
                        insert.setBytes(1, pending.payload());
                        if (pending.candidateSet() == null) {
                            insert.setString(2, null);
                        } else {
                            insert.setString(2, pending.candidateSet().id());
                        }
                        insert.setLong(3, System.currentTimeMillis());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
                committed = true;
                storageUnavailable.set(false);
                afterCommitForTest.run();
                settleCommitted(batch, insertedCandidateSets, duplicatesReleased);
                return;
            } catch (CandidateSnapshotCorruptionException e) {
                rollbackWriterConnection();
                failBatch(batch, new RequestLogUnavailableException(e.getMessage(), e));
                return;
            } catch (Error e) {
                if (committed) {
                    // 已經寫入：一定回報成功，不能讓呼叫端重試（會重複）
                    settleCommittedUntilDone(batch, insertedCandidateSets, duplicatesReleased);
                    logWriterError(e, 0);
                } else {
                    abandonUncommitted(batch, e);
                    TimeUnit.MILLISECONDS.sleep(retryMillis);
                }
                return;
            } catch (SQLException e) {
                storageUnavailable.set(true);
                updateBackpressureState();
                rollbackWriterConnection();
                closeWriterConnection();
                // Only this writer-owned batch has an uncertain commit result.
                // Entries still in the Java queue have never reached SQLite,
                // so fail them definitively instead of leaving every caller
                // blocked behind an unavailable disk. JMS rolls those entries
                // back to the broker and HTTP returns an explicit 503.
                failPending(new RequestLogUnavailableException(
                        "Request-log storage is temporarily unavailable; queued append was not written", e));
                log.warn("Request-log spool commit failed; retrying in {} ms: {}", retryMillis, e.getMessage());
                TimeUnit.MILLISECONDS.sleep(retryMillis);
            } finally {
                candidateSetLifecycleLock.readLock().unlock();
            }
        }
        failBatch(batch, new RequestLogUnavailableException("Request-log spool stopped before commit"));
    }

    private Map<String, CandidateSetPayload> uniqueCandidateSets(List<PendingAppend> batch) {
        Map<String, CandidateSetPayload> unique = new LinkedHashMap<>();
        for (PendingAppend pending : batch) {
            CandidateSetPayload candidateSet = pending.candidateSet();
            if (candidateSet == null) {
                continue;
            }
            CandidateSetPayload existing = unique.putIfAbsent(candidateSet.id(), candidateSet);
            if (existing != null && !Arrays.equals(existing.payload(), candidateSet.payload())) {
                throw new CandidateSnapshotCorruptionException(
                        "Candidate snapshot hash collision: " + candidateSet.id());
            }
        }
        return unique;
    }

    private Set<String> persistCandidateSets(
            Connection connection, Map<String, CandidateSetPayload> candidateSets) throws SQLException {
        Set<String> inserted = new java.util.HashSet<>();
        if (candidateSets.isEmpty()) {
            return inserted;
        }
        String insertSql = "INSERT OR IGNORE INTO candidate_snapshot_sets"
                + "(candidate_set_id, payload, created_at) VALUES (?, ?, ?)";
        String existingSql = "SELECT payload FROM candidate_snapshot_sets WHERE candidate_set_id = ?";
        try (PreparedStatement insert = connection.prepareStatement(insertSql);
             PreparedStatement existing = connection.prepareStatement(existingSql)) {
            for (CandidateSetPayload candidateSet : candidateSets.values()) {
                insert.setString(1, candidateSet.id());
                insert.setBytes(2, candidateSet.payload());
                insert.setLong(3, System.currentTimeMillis());
                if (insert.executeUpdate() == 1) {
                    inserted.add(candidateSet.id());
                    continue;
                }
                existing.setString(1, candidateSet.id());
                try (ResultSet row = existing.executeQuery()) {
                    if (!row.next() || !Arrays.equals(row.getBytes(1), candidateSet.payload())) {
                        throw new CandidateSnapshotCorruptionException(
                                "Candidate snapshot hash collision: " + candidateSet.id());
                    }
                }
            }
        }
        return inserted;
    }

    /** 已在暫存檔中的候選規則快照不必重複保留：回傳可以釋放的位元組。 */
    private static long duplicateCandidateBytes(List<PendingAppend> batch, Set<String> insertedCandidateSets) {
        Set<String> retained = new java.util.HashSet<>();
        long released = 0;
        for (PendingAppend pending : batch) {
            CandidateSetPayload candidateSet = pending.candidateSet();
            if (candidateSet == null) {
                continue;
            }
            boolean keepReservation = insertedCandidateSets.contains(candidateSet.id())
                    && retained.add(candidateSet.id());
            if (!keepReservation) {
                released += candidateSet.payload().length;
            }
        }
        return released;
    }

    private Connection getWriterConnection() throws SQLException {
        if (writerConnection != null && !writerConnection.isClosed()) {
            return writerConnection;
        }
        writerConnection = openConnection();
        try (Statement durability = writerConnection.createStatement()) {
            durability.execute("PRAGMA synchronous=" + synchronousMode);
        }
        writerConnection.setAutoCommit(false);
        return writerConnection;
    }

    private void rollbackWriterConnection() {
        if (writerConnection == null) {
            return;
        }
        try {
            writerConnection.rollback();
        } catch (SQLException e) {
            log.debug("Request-log spool rollback failed", e);
        }
    }

    private void closeWriterConnection() {
        if (writerConnection == null) {
            return;
        }
        try {
            writerConnection.close();
        } catch (SQLException e) {
            log.debug("Request-log spool writer connection close failed", e);
        } finally {
            writerConnection = null;
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + spoolPath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
            statement.execute("PRAGMA journal_size_limit=67108864");
        }
        return connection;
    }

    private void failPending(RuntimeException failure) {
        List<PendingAppend> pending = new ArrayList<>();
        appendQueue.drainTo(pending);
        failBatch(pending, failure);
    }

    /** 每筆只結算一次：可以重複執行（例如發生 Error 後重試），不會重複釋放容量。 */
    private void failBatch(List<PendingAppend> batch, RuntimeException failure) {
        for (PendingAppend pending : batch) {
            pending.committed().completeExceptionally(failure);
            releaseOnce(pending, pending.reservedBytes(), 1, pending.reservedBytes());
        }
        afterReleaseQuietly();
    }

    private static final class PendingAppend {
        private final byte[] payload;
        private final CandidateSetPayload candidateSet;
        private final long reservedBytes;
        private final CompletableFuture<Void> committed;
        /** 保留的容量是否已經釋放（只釋放一次）。 */
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();

        private PendingAppend(
                byte[] payload, CandidateSetPayload candidateSet,
                long reservedBytes, CompletableFuture<Void> committed) {
            this.payload = payload;
            this.candidateSet = candidateSet;
            this.reservedBytes = reservedBytes;
            this.committed = committed;
        }

        byte[] payload() {
            return payload;
        }

        CandidateSetPayload candidateSet() {
            return candidateSet;
        }

        long reservedBytes() {
            return reservedBytes;
        }

        CompletableFuture<Void> committed() {
            return committed;
        }

        /** 第一次呼叫回傳 true：由呼叫者釋放這筆保留的容量。 */
        boolean claimRelease() {
            return released.compareAndSet(false, true);
        }

        /** 釋放沒有生效（發生 Error）時撤回，讓重試再釋放一次。 */
        void unclaimRelease() {
            released.set(false);
        }
    }

    private static final class CandidateSnapshotCorruptionException extends RuntimeException {
        private CandidateSnapshotCorruptionException(String message) {
            super(message);
        }
    }

    /**
     * A spool head that cannot be replayed safely. The row is deliberately
     * retained so an operator can repair or export it; callers must not retry
     * the same head forever as though this were a transient database outage.
     */
    public static final class UnrecoverableSpoolEntryException
            extends RequestLogUnavailableException {

        public enum Kind {
            CORRUPT,
            OVERSIZED
        }

        private final long sequence;
        private final Kind kind;
        private final long rowBytes;
        private final long byteLimit;

        private UnrecoverableSpoolEntryException(
                long sequence,
                Kind kind,
                long rowBytes,
                long byteLimit,
                String detail,
                Throwable cause) {
            super("Cannot read request-log spool: sequence=" + sequence
                    + ", kind=" + kind + "; " + detail, cause);
            this.sequence = sequence;
            this.kind = kind;
            this.rowBytes = rowBytes;
            this.byteLimit = byteLimit;
        }

        public long sequence() {
            return sequence;
        }

        public Kind kind() {
            return kind;
        }

        public long rowBytes() {
            return rowBytes;
        }

        public long byteLimit() {
            return byteLimit;
        }
    }

    public record SpoolEntry(long sequence, LogTask task, int payloadBytes) {
    }

    private record CandidateRecord(
            String ruleId, String endpoint, String description, boolean enabled,
            String bodyCondition, String queryCondition, String headerCondition, int priority,
            String matchSpec) {

        /** matchSpec 放在最後並可為 null：舊版寫入的紀錄沒有這個欄位，讀回時為 null。 */
        static CandidateRecord from(CandidateSnapshot candidate) {
            return new CandidateRecord(candidate.getRuleId(), candidate.getEndpoint(),
                    candidate.getDescription(), candidate.isEnabled(), candidate.getBodyCondition(),
                    candidate.getQueryCondition(), candidate.getHeaderCondition(), candidate.getPriority(),
                    candidate.getMatchSpec());
        }

        CandidateSnapshot toCandidate() {
            return CandidateSnapshot.builder()
                    .ruleId(ruleId).endpoint(endpoint).description(description).enabled(enabled)
                    .bodyCondition(bodyCondition).queryCondition(queryCondition)
                    .headerCondition(headerCondition).matchSpec(matchSpec).priority(priority).build();
        }
    }

    private record CandidateSetRecord(List<CandidateRecord> candidates) {
    }

    private static final class CandidateSetPayload {
        private final String id;
        private final byte[] payload;

        private CandidateSetPayload(String id, byte[] payload) {
            this.id = id;
            this.payload = payload;
        }

        String id() {
            return id;
        }

        byte[] payload() {
            return payload;
        }
    }

    /** 空欄位不寫入（讀回時為 null），每筆紀錄少佔約百位元組；舊格式的紀錄照常讀取。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SpoolTask(
            String ruleId, Protocol protocol, String method, String endpoint, boolean matched,
            int responseTimeMs, Integer matchTimeMs, String clientIp, LocalDateTime requestTime,
            String matchChain, String targetHost, boolean forwarded, String forwardTarget,
            Integer proxyStatus, String proxyError,
            Integer responseStatus, String requestBody, String responseBody,
            String faultType, String scenarioName, String scenarioFromState, String scenarioToState,
            Integer candidateCount, Boolean matchedByFallback, Integer delayMs, Integer sseEventCount,
            String scenarioSkipped,
            List<CandidateRecord> candidates, String analysisBody, String queryString,
            Map<String, String> headers, Map<String, Boolean> matchOutcomes,
            boolean analysisUsesRequestBody, String diagnosticId) {

        static SpoolTask from(LogTask task) {
            boolean analysisUsesRequestBody = task.getRequestBody() != null
                    && Objects.equals(task.getRequestBody(), task.getAnalysisBody());
            return new SpoolTask(task.getRuleId(), task.getProtocol(), task.getMethod(), task.getEndpoint(),
                    task.isMatched(), task.getResponseTimeMs(), task.getMatchTimeMs(), task.getClientIp(),
                    task.getRequestTime(), task.getMatchChain(), task.getTargetHost(),
                    task.isForwarded(), task.getForwardTarget(), task.getProxyStatus(),
                    task.getProxyError(), task.getResponseStatus(), task.getRequestBody(), task.getResponseBody(),
                    task.getFaultType(), task.getScenarioName(), task.getScenarioFromState(), task.getScenarioToState(),
                    task.getCandidateCount(), task.getMatchedByFallback(), task.getDelayMs(), task.getSseEventCount(), task.getScenarioSkipped(),
                    null, analysisUsesRequestBody ? null : task.getAnalysisBody(),
                    task.getQueryString(), task.getHeaders(), task.getMatchOutcomes(),
                    analysisUsesRequestBody, task.getDiagnosticId());
        }

        LogTask toLogTask(List<CandidateRecord> externalCandidates) {
            List<CandidateRecord> restoredCandidates = externalCandidates != null
                    ? externalCandidates : candidates;
            return LogTask.builder()
                    .diagnosticId(diagnosticId)
                    .ruleId(ruleId).protocol(protocol).method(method).endpoint(endpoint).matched(matched)
                    .responseTimeMs(responseTimeMs).matchTimeMs(matchTimeMs).clientIp(clientIp)
                    .requestTime(requestTime).matchChain(matchChain).targetHost(targetHost)
                    .forwarded(forwarded).forwardTarget(forwardTarget)
                    .proxyStatus(proxyStatus).proxyError(proxyError).responseStatus(responseStatus)
                    .requestBody(requestBody).responseBody(responseBody)
                    .faultType(faultType).scenarioName(scenarioName)
                    .scenarioFromState(scenarioFromState).scenarioToState(scenarioToState)
                    .candidateCount(candidateCount).matchedByFallback(matchedByFallback).delayMs(delayMs).sseEventCount(sseEventCount)
                    .scenarioSkipped(scenarioSkipped)
                    .candidates(restoredCandidates == null ? List.of() : restoredCandidates.stream()
                            .map(CandidateRecord::toCandidate).toList())
                    .analysisBody(analysisUsesRequestBody ? requestBody : analysisBody)
                    .queryString(queryString).headers(headers)
                    .matchOutcomes(matchOutcomes).build();
        }
    }
}
