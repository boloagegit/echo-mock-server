package com.echo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class H2BackupServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private H2BackupService backupService;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        backupService = new H2BackupService(jdbcTemplate);
        
        // 使用反射設定私有欄位
        setField(backupService, "backupPath", tempDir.toString());
        setField(backupService, "retentionDays", 7);
        setField(backupService, "backupOnShutdown", true);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = H2BackupService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void backup_shouldExecuteBackupCommand() {
        // When
        String filename = backupService.backup("test");

        // Then
        verify(jdbcTemplate).execute(anyString());
        assertThat(filename).startsWith("echo-").endsWith(".zip");
    }

    @Test
    void listBackups_shouldReturnEmptyWhenNoBackups() {
        // When
        List<H2BackupService.BackupFile> files = backupService.listBackups();

        // Then
        assertThat(files).isEmpty();
    }

    @Test
    void listBackups_shouldReturnBackupFiles() throws IOException {
        // Given
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path backupFile = tempDir.resolve("echo-" + today + ".zip");
        Files.write(backupFile, "test".getBytes());

        // When
        List<H2BackupService.BackupFile> files = backupService.listBackups();

        // Then
        assertThat(files).hasSize(1);
        assertThat(files.get(0).name()).isEqualTo("echo-" + today + ".zip");
    }

    @Test
    void listBackups_shouldIgnoreNonBackupFiles() throws IOException {
        // Given
        Files.write(tempDir.resolve("other.txt"), "test".getBytes());
        Files.write(tempDir.resolve("echo-invalid.zip"), "test".getBytes());

        // When
        List<H2BackupService.BackupFile> files = backupService.listBackups();

        // Then
        assertThat(files).isEmpty();
    }

    @Test
    void getBackupPath_shouldReturnConfiguredPath() {
        assertThat(backupService.getBackupPath()).isEqualTo(tempDir.toString());
    }

    @Test
    void getRetentionDays_shouldReturnConfiguredDays() {
        assertThat(backupService.getRetentionDays()).isEqualTo(7);
    }
}
