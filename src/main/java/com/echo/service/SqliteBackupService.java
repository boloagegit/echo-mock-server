package com.echo.service;

import com.echo.config.SqliteStartupRecovery;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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

    @Value("${echo.backup.path:./backups}")
    private String backupPath;

    @Value("${echo.backup.retention-days:7}")
    private int retentionDays;

    @Value("${echo.backup.on-shutdown:true}")
    private boolean backupOnShutdown;

    @Autowired
    public SqliteBackupService(javax.sql.DataSource dataSource) {
        this(dataSource, Clock.systemDefaultZone());
    }

    SqliteBackupService(javax.sql.DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Scheduled(cron = "${echo.backup.cron:0 0 3 * * *}")
    public void scheduledBackup() {
        backup("scheduled");
    }

    @PreDestroy
    public void shutdownBackup() {
        if (backupOnShutdown) {
            backup("shutdown");
        }
    }

    @Override
    public String backup(String trigger) {
        Path staging = null;
        try {
            Path dir = Paths.get(backupPath);
            Files.createDirectories(dir);

            String filename = "echo-" + LocalDateTime.now(clock).format(FILE_TIME_FORMAT)
                    + "-" + UUID.randomUUID().toString().substring(0, 8) + ".sqlite";
            Path target = dir.resolve(filename);
            staging = dir.resolve(filename + ".part");

            // 使用 SQLite Online Backup API（透過 JDBC）
            // 這會產生一個包含所有已 commit 資料的完整一致性快照
            // 比 file copy 安全：file copy 可能漏掉 WAL 中未 checkpoint 的資料
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                stmt.executeUpdate("backup to '" + sqliteSqlPath(staging.toAbsolutePath()) + "'");
            }
            if (!SqliteStartupRecovery.isHealthyDatabase(staging)) {
                throw new IllegalStateException("SQLite backup integrity verification failed");
            }
            moveAtomically(staging, target);
            staging = null;
            log.info("SQLite backup completed: {} (trigger: {})", filename, trigger);

            cleanOldBackups();
            return filename;
        } catch (Exception e) {
            deleteQuietly(staging);
            log.error("SQLite backup failed", e);
            throw new RuntimeException("Backup failed: " + e.getMessage(), e);
        }
    }

    private void cleanOldBackups() {
        try {
            Path dir = Paths.get(backupPath);
            if (!Files.exists(dir)) {
                return;
            }

            LocalDate cutoff = LocalDate.now(clock).minusDays(retentionDays);

            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> fileName(p).matches(BACKUP_PATTERN))
                        .filter(p -> {
                            String name = fileName(p);
                            String dateStr = name.substring(5, 15);
                            LocalDate fileDate = LocalDate.parse(dateStr, DATE_FORMAT);
                            return fileDate.isBefore(cutoff);
                        })
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                                log.info("Deleted old backup: {}", fileName(p));
                            } catch (IOException e) {
                                log.warn("Failed to delete old backup: {}", fileName(p), e);
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("Failed to clean old backups", e);
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

    static String sqliteSqlPath(Path path) {
        return path.toString().replace("'", "''");
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
}
