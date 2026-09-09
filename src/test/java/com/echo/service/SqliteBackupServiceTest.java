package com.echo.service;

import com.echo.config.SqliteStartupRecovery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteBackupServiceTest {

    @TempDir
    Path tempDir;

    private Path backupDirectory;
    private SqliteBackupService backupService;

    @BeforeEach
    void setUp() throws Exception {
        Path database = tempDir.resolve("live.sqlite");
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database.toAbsolutePath());
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (value TEXT NOT NULL)");
            statement.execute("INSERT INTO marker(value) VALUES ('committed')");
        }
        backupDirectory = tempDir.resolve("backups");
        backupService = new SqliteBackupService(dataSource, Clock.fixed(
                Instant.parse("2026-09-09T12:34:56.789Z"), ZoneOffset.UTC));
        setField("backupPath", backupDirectory.toString());
        setField("retentionDays", 7);
        setField("backupOnShutdown", false);
    }

    @Test
    void backupCreatesUniqueVerifiedSnapshotsWithoutPartialFiles() throws Exception {
        String first = backupService.backup("test");
        String second = backupService.backup("test");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).matches("echo-2026-09-09-123456-789-[0-9a-f]{8}\\.sqlite");
        assertThat(SqliteStartupRecovery.isHealthyDatabase(backupDirectory.resolve(first))).isTrue();
        assertThat(SqliteStartupRecovery.isHealthyDatabase(backupDirectory.resolve(second))).isTrue();
        try (var files = Files.list(backupDirectory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(first, second)
                    .noneMatch(name -> name.endsWith(".part"));
        }
    }

    @Test
    void listBackupsSupportsLegacyAndTimestampedNamesOnly() throws Exception {
        String current = backupService.backup("test");
        Files.copy(backupDirectory.resolve(current), backupDirectory.resolve("echo-2026-09-08.sqlite"));
        Files.writeString(backupDirectory.resolve("echo-invalid.sqlite"), "ignore");

        assertThat(backupService.listBackups())
                .extracting(SqliteBackupService.BackupFile::name)
                .containsExactlyInAnyOrder(current, "echo-2026-09-08.sqlite");
    }

    @Test
    void sqliteBackupCommandEscapesApostrophesInPaths() {
        assertThat(SqliteBackupService.sqliteSqlPath(Path.of("/tmp/Echo User's Data/backup.sqlite")))
                .isEqualTo("/tmp/Echo User''s Data/backup.sqlite");
    }

    private void setField(String name, Object value) throws Exception {
        var field = SqliteBackupService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(backupService, value);
    }
}
