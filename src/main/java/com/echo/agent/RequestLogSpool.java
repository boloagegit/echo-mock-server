package com.echo.agent;

import com.echo.entity.Protocol;
import com.echo.service.RequestLogUnavailableException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Independent SQLite WAL used as the durable hand-off for request logs.
 *
 * <p>Producers wait only for a small group commit to this spool. The main H2/SQLite
 * database is updated by {@link LogAgent}, so slow queries or a temporary main DB
 * outage cannot silently discard accepted request logs.</p>
 */
@Component
@ConditionalOnProperty(name = "echo.request-log.store", havingValue = "database", matchIfMissing = true)
@Slf4j
public class RequestLogSpool {

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
    private final Cache<List<CandidateSnapshot>, CandidateSetPayload> candidateSetCache = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicLong reservedItems = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock candidateSetLifecycleLock = new ReentrantReadWriteLock();
    private final Set<String> knownCandidateSetIds = ConcurrentHashMap.newKeySet();

    private volatile String spoolId;
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
            @Value("${echo.request-log.durable.max-pending-bytes:10737418240}") long maxPendingBytes) {
        this.objectMapper = objectMapper;
        this.spoolPath = Path.of(spoolPath).toAbsolutePath().normalize();
        this.appendQueue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.appendBatchSize = Math.max(1, appendBatchSize);
        this.groupCommitMillis = Math.max(0, groupCommitMillis);
        this.queueOfferTimeoutMillis = Math.max(1, queueOfferTimeoutMillis);
        this.retryMillis = Math.max(10, retryMillis);
        this.maxPendingBytes = Math.max(1, maxPendingBytes);
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
        writerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "request-log-spool-writer");
            thread.setDaemon(true);
            return thread;
        });
        writerExecutor.execute(this::writerLoop);
        log.info("Request-log durable spool started: path={}, pendingBytes={}",
                spoolPath, reservedBytes.get());
    }

    @PreDestroy
    public void stop() {
        lifecycleLock.writeLock().lock();
        try {
            if (!running.compareAndSet(true, false)) {
                return;
            }
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
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    public String getSpoolId() {
        return spoolId;
    }

    /** Waits until the task has been committed with SQLite synchronous=FULL. */
    public void append(LogTask task) {
        PendingAppend pending;
        long reservedPayloadBytes = 0;
        lifecycleLock.readLock().lock();
        try {
            if (!running.get()) {
                throw new RequestLogUnavailableException("Request-log spool is not running");
            }

            final byte[] payload;
            final CandidateSetPayload candidateSet;
            try {
                payload = objectMapper.writeValueAsBytes(SpoolTask.from(task));
                candidateSet = candidateSetFor(task);
            } catch (IOException e) {
                throw new RequestLogUnavailableException("Cannot serialize request log", e);
            } catch (UncheckedIOException e) {
                throw new RequestLogUnavailableException("Cannot serialize candidate snapshots", e.getCause());
            }
            long bytes = (long) payload.length
                    + (candidateSet != null ? candidateSet.payload().length : 0);
            reserve(bytes);
            reservedPayloadBytes = bytes;

            pending = new PendingAppend(payload, candidateSet, bytes, new CompletableFuture<>());
            if (!appendQueue.offer(pending, queueOfferTimeoutMillis, TimeUnit.MILLISECONDS)) {
                reservedBytes.addAndGet(-bytes);
                reservedItems.decrementAndGet();
                reservedPayloadBytes = 0;
                throw new RequestLogUnavailableException("Request-log spool is at capacity");
            }
        } catch (InterruptedException e) {
            if (reservedPayloadBytes > 0) {
                reservedBytes.addAndGet(-reservedPayloadBytes);
                reservedItems.decrementAndGet();
            }
            Thread.currentThread().interrupt();
            throw new RequestLogUnavailableException("Interrupted while accepting request log", e);
        } finally {
            lifecycleLock.readLock().unlock();
        }

        try {
            pending.committed().join();
        } catch (CompletionException e) {
            throw new RequestLogUnavailableException("Cannot commit request log to durable spool", e.getCause());
        }
    }

    private CandidateSetPayload candidateSetFor(LogTask task) {
        List<CandidateSnapshot> candidates = task.getCandidates();
        if (candidates.isEmpty()) {
            return null;
        }
        return candidateSetCache.get(candidates, this::serializeCandidateSet);
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

    public List<SpoolEntry> readAfter(long sequence, int limit) {
        String sql = "SELECT sequence_id, payload, candidate_set_id FROM request_log_spool "
                + "WHERE sequence_id > ? ORDER BY sequence_id LIMIT ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sequence);
            statement.setInt(2, Math.max(1, limit));
            List<SpoolEntry> result = new ArrayList<>();
            Map<String, List<CandidateRecord>> candidateCache = new LinkedHashMap<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] payload = rows.getBytes(2);
                    SpoolTask stored = objectMapper.readValue(payload, SpoolTask.class);
                    String candidateSetId = rows.getString(3);
                    List<CandidateRecord> candidates = candidateSetId == null ? null
                            : loadCandidateSet(connection, candidateSetId, candidateCache);
                    result.add(new SpoolEntry(rows.getLong(1), stored.toLogTask(candidates), payload.length));
                }
            }
            return result;
        } catch (SQLException | IOException e) {
            throw new RequestLogUnavailableException("Cannot read request-log spool", e);
        }
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
            if (released > 0) {
                long releasedBytes = released;
                reservedBytes.updateAndGet(current -> Math.max(0, current - releasedBytes));
            }
            if (deletedRows > 0) {
                reservedItems.updateAndGet(current -> Math.max(0, current - deletedRows));
            }
            cleanupCandidateSetsIfIdle();
        } catch (SQLException e) {
            throw new RequestLogUnavailableException(
                    "Cannot clean request-log spool: " + e.getMessage(), e);
        }
    }

    private void cleanupCandidateSetsIfIdle() throws SQLException {
        if (reservedItems.get() != 0) {
            return;
        }
        candidateSetLifecycleLock.writeLock().lock();
        try {
            if (reservedItems.get() != 0) {
                return;
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                long released = 0;
                try (ResultSet row = statement.executeQuery(
                        "SELECT COALESCE(SUM(length(payload)), 0) FROM candidate_snapshot_sets")) {
                    if (row.next()) {
                        released = row.getLong(1);
                    }
                }
                statement.executeUpdate("DELETE FROM candidate_snapshot_sets");
                knownCandidateSetIds.clear();
                if (released > 0) {
                    long releasedBytes = released;
                    reservedBytes.updateAndGet(current -> Math.max(0, current - releasedBytes));
                }
            }
        } finally {
            candidateSetLifecycleLock.writeLock().unlock();
        }
    }

    long pendingBytes() {
        return reservedBytes.get();
    }

    long pendingItems() {
        return reservedItems.get();
    }

    private void initializeDatabase() {
        try {
            Path parent = spoolPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
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
                knownCandidateSetIds.clear();
                try (ResultSet candidates = statement.executeQuery(
                        "SELECT candidate_set_id FROM candidate_snapshot_sets")) {
                    while (candidates.next()) {
                        knownCandidateSetIds.add(candidates.getString(1));
                    }
                }
                spoolId = loadOrCreateSpoolId(connection);
                try (ResultSet row = statement.executeQuery(
                        "SELECT COUNT(*), "
                                + "COALESCE(SUM(length(payload)), 0) "
                                + "+ COALESCE((SELECT SUM(length(payload)) FROM candidate_snapshot_sets), 0) "
                                + "FROM request_log_spool")) {
                    if (row.next()) {
                        reservedItems.set(row.getLong(1));
                        reservedBytes.set(row.getLong(2));
                    }
                }
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

    private void reserve(long bytes) {
        while (true) {
            long current = reservedBytes.get();
            if (bytes > maxPendingBytes - current) {
                throw new RequestLogUnavailableException(
                        "Request-log durable spool reached its configured byte limit");
            }
            if (reservedBytes.compareAndSet(current, current + bytes)) {
                reservedItems.incrementAndGet();
                return;
            }
        }
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
                    batch = new ArrayList<>(appendBatchSize);
                } catch (InterruptedException e) {
                    if (running.get()) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            closeWriterConnection();
        }
        if (!batch.isEmpty()) {
            failBatch(batch, new RequestLogUnavailableException("Request-log spool stopped"));
        }
    }

    private void commitWithRetry(List<PendingAppend> batch) throws InterruptedException {
        while (running.get()) {
            candidateSetLifecycleLock.readLock().lock();
            try {
                Connection connection = getWriterConnection();
                Map<String, CandidateSetPayload> candidateSets = uniqueCandidateSets(batch);
                candidateSets.keySet().removeIf(knownCandidateSetIds::contains);
                Set<String> insertedCandidateSets = persistCandidateSets(connection, candidateSets);
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
                knownCandidateSetIds.addAll(candidateSets.keySet());
                releaseDuplicateCandidateReservations(batch, insertedCandidateSets);
                batch.forEach(item -> item.committed().complete(null));
                return;
            } catch (CandidateSnapshotCorruptionException e) {
                rollbackWriterConnection();
                failBatch(batch, new RequestLogUnavailableException(e.getMessage(), e));
                return;
            } catch (SQLException e) {
                rollbackWriterConnection();
                closeWriterConnection();
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

    private void releaseDuplicateCandidateReservations(
            List<PendingAppend> batch, Set<String> insertedCandidateSets) {
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
        if (released > 0) {
            reservedBytes.addAndGet(-released);
        }
    }

    private Connection getWriterConnection() throws SQLException {
        if (writerConnection != null && !writerConnection.isClosed()) {
            return writerConnection;
        }
        writerConnection = openConnection();
        try (Statement durability = writerConnection.createStatement()) {
            durability.execute("PRAGMA synchronous=FULL");
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

    private void failBatch(List<PendingAppend> batch, RuntimeException failure) {
        for (PendingAppend pending : batch) {
            reservedBytes.addAndGet(-pending.reservedBytes());
            reservedItems.decrementAndGet();
            pending.committed().completeExceptionally(failure);
        }
    }

    private static final class PendingAppend {
        private final byte[] payload;
        private final CandidateSetPayload candidateSet;
        private final long reservedBytes;
        private final CompletableFuture<Void> committed;

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
    }

    private static final class CandidateSnapshotCorruptionException extends RuntimeException {
        private CandidateSnapshotCorruptionException(String message) {
            super(message);
        }
    }

    public record SpoolEntry(long sequence, LogTask task, int payloadBytes) {
    }

    private record CandidateRecord(
            String ruleId, String endpoint, String description, boolean enabled,
            String bodyCondition, String queryCondition, String headerCondition, int priority) {

        static CandidateRecord from(CandidateSnapshot candidate) {
            return new CandidateRecord(candidate.getRuleId(), candidate.getEndpoint(),
                    candidate.getDescription(), candidate.isEnabled(), candidate.getBodyCondition(),
                    candidate.getQueryCondition(), candidate.getHeaderCondition(), candidate.getPriority());
        }

        CandidateSnapshot toCandidate() {
            return CandidateSnapshot.builder()
                    .ruleId(ruleId).endpoint(endpoint).description(description).enabled(enabled)
                    .bodyCondition(bodyCondition).queryCondition(queryCondition)
                    .headerCondition(headerCondition).priority(priority).build();
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

    private record SpoolTask(
            String ruleId, Protocol protocol, String method, String endpoint, boolean matched,
            int responseTimeMs, Integer matchTimeMs, String clientIp, LocalDateTime requestTime,
            String matchChain, String targetHost, Integer proxyStatus, String proxyError,
            Integer responseStatus, String requestBody, String responseBody,
            String faultType, String scenarioName, String scenarioFromState, String scenarioToState,
            List<CandidateRecord> candidates, String analysisBody, String queryString,
            Map<String, String> headers, Map<String, Boolean> matchOutcomes,
            boolean analysisUsesRequestBody) {

        static SpoolTask from(LogTask task) {
            boolean analysisUsesRequestBody = task.getRequestBody() != null
                    && Objects.equals(task.getRequestBody(), task.getAnalysisBody());
            return new SpoolTask(task.getRuleId(), task.getProtocol(), task.getMethod(), task.getEndpoint(),
                    task.isMatched(), task.getResponseTimeMs(), task.getMatchTimeMs(), task.getClientIp(),
                    task.getRequestTime(), task.getMatchChain(), task.getTargetHost(), task.getProxyStatus(),
                    task.getProxyError(), task.getResponseStatus(), task.getRequestBody(), task.getResponseBody(),
                    task.getFaultType(), task.getScenarioName(), task.getScenarioFromState(), task.getScenarioToState(),
                    null, analysisUsesRequestBody ? null : task.getAnalysisBody(),
                    task.getQueryString(), task.getHeaders(), task.getMatchOutcomes(),
                    analysisUsesRequestBody);
        }

        LogTask toLogTask(List<CandidateRecord> externalCandidates) {
            List<CandidateRecord> restoredCandidates = externalCandidates != null
                    ? externalCandidates : candidates;
            return LogTask.builder()
                    .ruleId(ruleId).protocol(protocol).method(method).endpoint(endpoint).matched(matched)
                    .responseTimeMs(responseTimeMs).matchTimeMs(matchTimeMs).clientIp(clientIp)
                    .requestTime(requestTime).matchChain(matchChain).targetHost(targetHost)
                    .proxyStatus(proxyStatus).proxyError(proxyError).responseStatus(responseStatus)
                    .requestBody(requestBody).responseBody(responseBody)
                    .faultType(faultType).scenarioName(scenarioName)
                    .scenarioFromState(scenarioFromState).scenarioToState(scenarioToState)
                    .candidates(restoredCandidates == null ? List.of() : restoredCandidates.stream()
                            .map(CandidateRecord::toCandidate).toList())
                    .analysisBody(analysisUsesRequestBody ? requestBody : analysisBody)
                    .queryString(queryString).headers(headers)
                    .matchOutcomes(matchOutcomes).build();
        }
    }
}
