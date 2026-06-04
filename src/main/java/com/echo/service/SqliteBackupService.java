package com.echo.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * SQLite 備份服務
 * <p>
 * SQLite 在 WAL mode 下可以安全地直接 copy 檔案作為 hot backup。
 * 不需要 SHUTDOWN COMPACT，SQLite 的 VACUUM 是 atomic 的。
 */
@Service
@ConditionalOnProperty(name = "echo.backup.enabled", havingValue = "true")
@ConditionalOnExpression("'${spring.datasource.url:}'.contains(':sqlite:')")
public class SqliteBackupService implements BackupService {

    private static final Logger log = LoggerFactory.getLogger(SqliteBackupService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final javax.sql.DataSource dataSource;

    @Value("${echo.backup.path:./backups}")
    private String backupPath;

    @Value("${echo.backup.retention-days:7}")
    private int retentionDays;

    @Value("${echo.backup.on-shutdown:true}")
    private boolean backupOnShutdown;

    public SqliteBackupService(javax.sql.DataSource dataSource) {
        this.dataSource = dataSource;
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
        try {
            Path dir = Paths.get(backupPath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            String filename = "echo-" + LocalDate.now().format(DATE_FORMAT) + ".sqlite";
            Path target = dir.resolve(filename);

            // 使用 SQLite Online Backup API（透過 JDBC）
            // 這會產生一個包含所有已 commit 資料的完整一致性快照
            // 比 file copy 安全：file copy 可能漏掉 WAL 中未 checkpoint 的資料
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                stmt.executeUpdate("backup to '" + target.toAbsolutePath() + "'");
            }
            log.info("SQLite backup completed: {} (trigger: {})", filename, trigger);

            cleanOldBackups();
            return filename;
        } catch (Exception e) {
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

            LocalDate cutoff = LocalDate.now().minusDays(retentionDays);

            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().matches("echo-\\d{4}-\\d{2}-\\d{2}\\.sqlite"))
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            String dateStr = name.substring(5, 15);
                            LocalDate fileDate = LocalDate.parse(dateStr, DATE_FORMAT);
                            return fileDate.isBefore(cutoff);
                        })
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                                log.info("Deleted old backup: {}", p.getFileName());
                            } catch (IOException e) {
                                log.warn("Failed to delete old backup: {}", p.getFileName(), e);
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
                return files.filter(p -> p.getFileName().toString().matches("echo-\\d{4}-\\d{2}-\\d{2}\\.sqlite"))
                        .map(p -> {
                            try {
                                return new BackupFile(
                                        p.getFileName().toString(),
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
