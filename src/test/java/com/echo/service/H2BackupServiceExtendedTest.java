package com.echo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class H2BackupServiceExtendedTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private H2BackupService backupService;

    private Path fakeDbFile;

    @BeforeEach
    void setUp() throws IOException {
        jdbcTemplate = mock(JdbcTemplate.class);
        backupService = new H2BackupService(jdbcTemplate);
        ReflectionTestUtils.setField(backupService, "backupPath", tempDir.toString());
        ReflectionTestUtils.setField(backupService, "retentionDays", 7);
        ReflectionTestUtils.setField(backupService, "backupOnShutdown", true);

        fakeDbFile = tempDir.resolve("mockdb.mv.db");
        Files.write(fakeDbFile, new byte[1024]);
        ReflectionTestUtils.setField(backupService, "dbFilePath", fakeDbFile.toString());
    }

    @Test
    void scheduledBackup_shouldCallBackup() {
        backupService.scheduledBackup();
        verify(jdbcTemplate, times(2)).execute(anyString()); // backup + compact
    }

    @Test
    void shutdownBackup_shouldCallBackup_whenEnabled() {
        backupService.shutdownBackup();
        verify(jdbcTemplate).execute(anyString());
    }

    @Test
    void shutdownBackup_shouldSkip_whenDisabled() {
        ReflectionTestUtils.setField(backupService, "backupOnShutdown", false);
        backupService.shutdownBackup();
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void backup_shouldCleanOldBackups() throws IOException {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String oldDate = LocalDate.now().minusDays(30).format(fmt);
        Path oldFile = tempDir.resolve("echo-" + oldDate + ".zip");
        Files.write(oldFile, "old".getBytes());

        backupService.backup("test");

        assertThat(oldFile).doesNotExist();
    }

    @Test
    void backup_shouldKeepRecentBackups() throws IOException {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String recentDate = LocalDate.now().minusDays(1).format(fmt);
        Path recentFile = tempDir.resolve("echo-" + recentDate + ".zip");
        Files.write(recentFile, "recent".getBytes());

        backupService.backup("test");

        assertThat(recentFile).exists();
    }

    @Test
    void backup_shouldThrow_whenJdbcFails() {
        doThrow(new RuntimeException("DB error")).when(jdbcTemplate).execute(anyString());

        assertThatThrownBy(() -> backupService.backup("test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Backup failed");
    }

    @Test
    void backup_shouldCreateDirectory_whenNotExists() {
        Path newDir = tempDir.resolve("subdir");
        ReflectionTestUtils.setField(backupService, "backupPath", newDir.toString());

        backupService.backup("test");

        assertThat(newDir).exists();
    }

    @Test
    void listBackups_shouldReturnEmpty_whenDirNotExists() {
        ReflectionTestUtils.setField(backupService, "backupPath", tempDir.resolve("nonexistent").toString());

        assertThat(backupService.listBackups()).isEmpty();
    }

    @Test
    void listBackups_shouldSortByTimeDescending() throws Exception {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String date1 = LocalDate.now().minusDays(2).format(fmt);
        String date2 = LocalDate.now().minusDays(1).format(fmt);
        String date3 = LocalDate.now().format(fmt);

        Path f1 = tempDir.resolve("echo-" + date1 + ".zip");
        Path f2 = tempDir.resolve("echo-" + date2 + ".zip");
        Path f3 = tempDir.resolve("echo-" + date3 + ".zip");
        Files.write(f1, "a".getBytes());
        Files.write(f2, "bb".getBytes());
        Files.write(f3, "ccc".getBytes());

        // Explicitly set lastModifiedTime to guarantee ordering
        Files.setLastModifiedTime(f1, java.nio.file.attribute.FileTime.fromMillis(1000));
        Files.setLastModifiedTime(f2, java.nio.file.attribute.FileTime.fromMillis(2000));
        Files.setLastModifiedTime(f3, java.nio.file.attribute.FileTime.fromMillis(3000));

        var files = backupService.listBackups();

        assertThat(files).hasSize(3);
        // Sorted by time descending — most recent first
        assertThat(files.get(0).name()).contains(date3);
        assertThat(files.get(1).name()).contains(date2);
        assertThat(files.get(2).name()).contains(date1);
    }

    @Test
    void compact_shouldReturnNull_whenFails() {
        // Point to non-existent file so Files.size() throws
        ReflectionTestUtils.setField(backupService, "dbFilePath", tempDir.resolve("nonexistent.mv.db").toString());
        var result = backupService.compact();
        assertThat(result).isNull();
    }

    @Test
    void compact_shouldReturnCompactResult_whenSucceeds() {
        var result = backupService.compact();
        assertThat(result).isNotNull();
        assertThat(result.sizeBefore()).isEqualTo(1024);
        assertThat(result.sizeAfter()).isEqualTo(1024);
    }
}
