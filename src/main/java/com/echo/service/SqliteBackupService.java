package com.echo.service;

import com.echo.config.SqliteStartupRecovery;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.ExtendedCommand;
import org.sqlite.SQLiteConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * SQLite 備份服務
 * <p>
 * Uses SQLite's online backup command so committed WAL content is included in
 * a consistent snapshot. A snapshot is published only after integrity and
 * foreign-key verification succeeds.
 */
@Service
@ConditionalOnProperty(name = "echo.backup.enabled", havingValue = "true")
@ConditionalOnExpression("'${spring.datasource.url:}'.contains(':sqlite:')")
public class SqliteBackupService implements BackupService {

    private static final Logger log = LoggerFactory.getLogger(SqliteBackupService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss-SSS");
    private static final String BACKUP_PATTERN =
            "echo-\\d{4}-\\d{2}-\\d{2}(?:-\\d{6}-\\d{3}-[0-9a-f]{8})?\\.sqlite";

    private final javax.sql.DataSource dataSource;
    private final Clock clock;
    private final DiskSpace diskSpace;
    private final ReentrantLock maintenanceLock = new ReentrantLock();

    @Value("${echo.backup.path:./backups}")
    private String backupPath;

    @Value("${echo.backup.retention-days:7}")
    private int retentionDays;

    @Value("${echo.backup.on-shutdown:true}")
    private boolean backupOnShutdown;

    @Value("${echo.backup.max-total-bytes:10737418240}")
    private long maxTotalBytes = 10L * 1024 * 1024 * 1024;

    @Value("${echo.backup.min-free-bytes:536870912}")
    private long minFreeBytes = 512L * 1024 * 1024;

    @Value("${echo.backup.max-files:100}")
    private int maxFiles = 100;

    @Autowired
    public SqliteBackupService(javax.sql.DataSource dataSource) {
        this(dataSource, Clock.systemDefaultZone());
    }

    SqliteBackupService(javax.sql.DataSource dataSource, Clock clock) {
        this(dataSource, clock, directory -> Files.getFileStore(directory).getUsableSpace());
    }

    SqliteBackupService(javax.sql.DataSource dataSource, Clock clock, DiskSpace diskSpace) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.diskSpace = diskSpace;
    }

    @PostConstruct
    void validateSettings() {
        if (retentionDays < 1 || maxTotalBytes < 1 || minFreeBytes < 0 || maxFiles < 1) {
            throw new IllegalArgumentException("Backup retention-days and max-total-bytes must be positive; "
                    + "max-files must be positive; min-free-bytes must not be negative");
        }
    }

    @Scheduled(cron = "${echo.backup.cron:0 0 3 * * *}")
    public void scheduledBackup() {
        // Do not lose a daily recovery point just because housekeeping is running.
        // Waiting is confined to this background job and can be cancelled at shutdown.
        try {
            maintenanceLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Scheduled SQLite backup interrupted while waiting for maintenance");
            return;
        }
        try {
            createBackup("scheduled");
        } finally {
            maintenanceLock.unlock();
        }
    }

    /** Expiry and capacity cleanup must keep working even while creating new backups fails. */
    @Scheduled(initialDelayString = "${echo.backup.housekeeping-initial-delay-ms:60000}",
            fixedDelayString = "${echo.backup.housekeeping-interval-ms:3600000}")
    public void housekeeping() {
        if (!maintenanceLock.tryLock()) {
            return;
        }
        try {
            Path dir = Paths.get(backupPath);
            if (Files.isDirectory(dir)) {
                cleanBackups(dir, 0, null);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to clean SQLite backups: {}", e.getMessage());
        } finally {
            maintenanceLock.unlock();
        }
    }

    @PreDestroy
    public void shutdownBackup() {
        if (backupOnShutdown) {
            backup("shutdown");
        }
    }

    @Override
    public String backup(String trigger) {
        if (!maintenanceLock.tryLock()) {
            throw new IllegalStateException("A SQLite backup or backup housekeeping operation is already running");
        }
        try {
            return createBackup(trigger);
        } finally {
            maintenanceLock.unlock();
        }
    }

    private String createBackup(String trigger) {
        Path staging = null;
        try {
            Path dir = Paths.get(backupPath);
            Files.createDirectories(dir);
            // Run before touching the live database: an unavailable source or a failed copy
            // must not prevent expiry. Keep the newest verified recovery point throughout.
            Retention retained = cleanBackups(dir, 0, null);

            String filename = "echo-" + LocalDateTime.now(clock).format(FILE_TIME_FORMAT)
                    + "-" + UUID.randomUUID().toString().substring(0, 8) + ".sqlite";
            Path target = dir.resolve(filename);
            staging = dir.resolve(filename + ".part");

            // 使用 SQLite Online Backup API（透過 JDBC）
            // 這會產生一個包含所有已 commit 資料的完整一致性快照
            // 比 file copy 安全：file copy 可能漏掉 WAL 中未 checkpoint 的資料
            try (var conn = dataSource.getConnection()) {
                long expectedBytes = snapshotBytes(conn);
                if (expectedBytes > maxTotalBytes) {
                    throw new IOException("SQLite snapshot exceeds echo.backup.max-total-bytes");
                }
                Retention reserved = cleanBackups(dir, expectedBytes, retained.protectedBackup());
                if (reserved.bytes() > maxTotalBytes - expectedBytes) {
                    throw new IOException("Backup capacity cannot hold a new snapshot while preserving "
                            + "the last verified backup");
                }
                long free = diskSpace.usableBytes(dir);
                if (free < minFreeBytes || expectedBytes > free - minFreeBytes) {
                    throw new IOException("Insufficient backup disk space to preserve echo.backup.min-free-bytes");
                }
                // The driver's BACKUP command parser does not support SQL quote escaping.
                // Pass the filename directly to the same online-backup implementation.
                var sqlite = conn.unwrap(SQLiteConnection.class);
                new ExtendedCommand.BackupCommand("main", staging.toAbsolutePath().toString())
                        .execute(sqlite.getDatabase());
            }
            if (!SqliteStartupRecovery.isHealthyDatabase(staging)) {
                throw new IllegalStateException("SQLite backup integrity verification failed");
            }
            if (Files.size(staging) > maxTotalBytes) {
                throw new IOException("Completed SQLite snapshot exceeds echo.backup.max-total-bytes");
            }
            moveAtomically(staging, target);
            staging = null;
            log.info("SQLite backup completed: {} (trigger: {})", filename, trigger);

            try {
                cleanBackups(dir, 0, target);
            } catch (IOException | RuntimeException cleanupFailure) {
                // The verified snapshot has already been published. Do not report an
                // ambiguous backup failure just because follow-up retention could not run.
                log.warn("SQLite backup was published but retention cleanup failed: {}", cleanupFailure.getMessage());
            }
            return filename;
        } catch (IOException | SQLException | RuntimeException e) {
            deleteQuietly(staging);
            log.error("SQLite backup failed", e);
            throw new RuntimeException("Backup failed: " + e.getMessage(), e);
        }
    }

    private Retention cleanBackups(Path dir, long incomingBytes, Path verifiedBackup) throws IOException {
        List<OwnedBackup> files = ownedBackups(dir);
        long total = 0;
        for (OwnedBackup file : files) {
            total = Math.addExact(total, file.size());
        }
        Path protectedBackup = verifiedBackup;
        if (protectedBackup == null) {
            // Files are oldest first. Do not let a newer corrupt file displace a usable copy.
            for (int i = files.size() - 1; i >= 0; i--) {
                Path candidate = files.get(i).path();
                if (SqliteStartupRecovery.isHealthyDatabase(candidate)) {
                    protectedBackup = candidate;
                    break;
                }
            }
        }
        if (protectedBackup == null && !files.isEmpty()) {
            // An unavailable filesystem can make verification fail transiently. Avoid deleting
            // possible recovery material until at least one backup has been verified.
            log.warn("No SQLite backup could be verified; existing backup files were preserved");
            return new Retention(total, null);
        }
        LocalDate cutoff = LocalDate.now(clock).minusDays(retentionDays);
        long budget = Math.max(0, maxTotalBytes - incomingBytes);
        int count = files.size();
        for (OwnedBackup file : files) {
            if (file.path().equals(protectedBackup)) {
                continue;
            }
            if (file.date().isBefore(cutoff) || total > budget || count > maxFiles) {
                try {
                    Files.deleteIfExists(file.path());
                    total -= file.size();
                    count--;
                    log.info("Deleted SQLite backup: {} (retention/capacity)", fileName(file.path()));
                } catch (IOException e) {
                    log.warn("Failed to delete SQLite backup {}: {}", fileName(file.path()), e.getMessage());
                }
            }
        }
        return new Retention(total, protectedBackup);
    }

    private static List<OwnedBackup> ownedBackups(Path dir) throws IOException {
        List<OwnedBackup> backups = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            Iterator<Path> entries = files.iterator();
            while (entries.hasNext()) {
                Path file = entries.next();
                String name = fileName(file);
                if (!name.matches(BACKUP_PATTERN)) {
                    continue;
                }
                LocalDate date;
                try {
                    date = LocalDate.parse(name.substring(5, 15), DATE_FORMAT);
                } catch (DateTimeParseException invalidName) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) {
                    backups.add(new OwnedBackup(file, date, attributes.size(),
                            attributes.lastModifiedTime().toMillis()));
                }
            }
        }
        backups.sort(Comparator.comparingLong(OwnedBackup::modifiedMillis)
                .thenComparing(file -> fileName(file.path())));
        return backups;
    }

    private static long snapshotBytes(Connection connection) throws SQLException {
        return Math.multiplyExact(pragma(connection, "page_count"), pragma(connection, "page_size"));
    }

    private static long pragma(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA " + name)) {
            if (!rows.next() || rows.getLong(1) <= 0) {
                throw new SQLException("SQLite returned an invalid " + name);
            }
            return rows.getLong(1);
        }
    }

    @Override
    public List<BackupFile> listBackups() {
        try {
            Path dir = Paths.get(backupPath);
            if (!Files.exists(dir)) {
                return List.of();
            }

            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(p -> fileName(p).matches(BACKUP_PATTERN))
                        .map(p -> {
                            try {
                                return new BackupFile(
                                        fileName(p),
                                        Files.size(p),
                                        LocalDateTime.ofInstant(
                                                Files.getLastModifiedTime(p).toInstant(),
                                                ZoneId.systemDefault()
                                        )
                                );
                            } catch (IOException e) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(BackupFile::time).reversed())
                        .toList();
            }
        } catch (IOException e) {
            log.warn("Failed to list backups", e);
            return List.of();
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name != null ? name.toString() : "";
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The original backup failure remains the actionable error.
        }
    }

    @Override
    public String getBackupPath() {
        return backupPath;
    }

    @Override
    public int getRetentionDays() {
        return retentionDays;
    }

    public record BackupFile(String name, long size, LocalDateTime time) implements BackupService.BackupFileInfo {}

    private record OwnedBackup(Path path, LocalDate date, long size, long modifiedMillis) { }
    private record Retention(long bytes, Path protectedBackup) { }

    @FunctionalInterface
    interface DiskSpace {
        long usableBytes(Path directory) throws IOException;
    }
}
