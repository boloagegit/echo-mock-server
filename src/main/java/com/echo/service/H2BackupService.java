package com.echo.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
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

@Service
@ConditionalOnProperty(name = "echo.backup.enabled", havingValue = "true")
@ConditionalOnExpression("'${spring.datasource.url:}'.contains(':h2:')")
public class H2BackupService {

    private static final Logger log = LoggerFactory.getLogger(H2BackupService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbcTemplate;

    @Value("${echo.backup.path:./backups}")
    private String backupPath;

    @Value("${echo.backup.retention-days:7}")
    private int retentionDays;

    @Value("${echo.backup.on-shutdown:true}")
    private boolean backupOnShutdown;

    public H2BackupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${echo.backup.cron:0 0 3 * * *}")
    public void scheduledBackup() {
        backup("scheduled");
        compact();
    }

    /**
     * 執行 SHUTDOWN COMPACT 回收 H2 已刪除資料的磁碟空間。
     * H2 會關閉並重寫檔案，HikariCP 自動重建連線。
     */
    public CompactResult compact() {
        try {
            long sizeBefore = Files.size(Path.of(dbFilePath()));
            jdbcTemplate.execute("SHUTDOWN COMPACT");
            long sizeAfter = Files.size(Path.of(dbFilePath()));
            log.info("H2 compact completed: {} MB -> {} MB",
                    sizeBefore / 1024 / 1024, sizeAfter / 1024 / 1024);
            return new CompactResult(sizeBefore, sizeAfter);
        } catch (Exception e) {
            log.warn("H2 compact failed (will retry next cycle): {}", e.getMessage());
            return null;
        }
    }

    public record CompactResult(long sizeBefore, long sizeAfter) {}

    @Value("${echo.backup.db-file-path:./mockdb.mv.db}")
    private String dbFilePath = "./mockdb.mv.db";

    String dbFilePath() {
        return dbFilePath;
    }

    @PreDestroy
    public void shutdownBackup() {
        if (backupOnShutdown) {
            backup("shutdown");
        }
    }

    public String backup(String trigger) {
        try {
            Path dir = Paths.get(backupPath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            String filename = "echo-" + LocalDate.now().format(DATE_FORMAT) + ".zip";
            Path filePath = dir.resolve(filename);

            // H2 BACKUP TO 指令
            jdbcTemplate.execute("BACKUP TO '" + filePath.toAbsolutePath() + "'");
            log.info("H2 backup completed: {} (trigger: {})", filename, trigger);

            cleanOldBackups();
            return filename;
        } catch (Exception e) {
            log.error("H2 backup failed", e);
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
                files.filter(p -> p.getFileName().toString().matches("echo-\\d{4}-\\d{2}-\\d{2}\\.zip"))
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            String dateStr = name.substring(5, 15); // echo-YYYY-MM-DD.zip
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

    public List<BackupFile> listBackups() {
        try {
            Path dir = Paths.get(backupPath);
            if (!Files.exists(dir)) {
                return List.of();
            }

            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(p -> p.getFileName().toString().matches("echo-\\d{4}-\\d{2}-\\d{2}\\.zip"))
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

    public String getBackupPath() {
        return backupPath;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public record BackupFile(String name, long size, LocalDateTime time) {}
}
