package com.echo.service;

import com.echo.config.SqliteStartupRecovery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqliteBackupRetentionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;
    private Path database;
    private Path backups;
    private DataSource source;
    private SqliteBackupService service;

    @BeforeEach
    void setUp() throws Exception {
        database = directory.resolve("Echo User's Data.sqlite");
        backups = Files.createDirectories(directory.resolve("Echo User's Backups"));
        source = new DriverManagerDataSource("jdbc:sqlite:" + database.toAbsolutePath());
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker(value TEXT NOT NULL)");
            statement.execute("INSERT INTO marker VALUES ('committed')");
        }
        service = service(source, dir -> Long.MAX_VALUE);
    }

    @Test
    void failedBackupStillExpiresOlderFilesAndPreservesTheLastVerifiedCopy() throws Exception {
        Path old = owned("echo-2026-09-01.sqlite", 1_000);
        Path lastGood = owned("echo-2026-09-02.sqlite", 2_000);
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new SQLException("source unavailable"));
        service = service(unavailable, dir -> Long.MAX_VALUE);

        assertThatThrownBy(() -> service.backup("test"))
                .hasMessageContaining("source unavailable");

        assertThat(old).doesNotExist();
        assertThat(lastGood).exists();
        assertThat(SqliteStartupRecovery.isHealthyDatabase(lastGood)).isTrue();
        assertNoPartialFiles();
    }

    @Test
    void independentHousekeepingDoesNotNeedTheLiveDatabase() throws Exception {
        Path old = owned("echo-2026-09-01.sqlite", 1_000);
        Path latest = owned("echo-2026-10-18.sqlite", 2_000);
        DataSource unavailable = mock(DataSource.class);
        service = service(unavailable, dir -> Long.MAX_VALUE);

        service.housekeeping();

        assertThat(old).doesNotExist();
        assertThat(latest).exists();
        verifyNoInteractions(unavailable);
    }

    @Test
    void aNewerCorruptFileNeverDisplacesTheLastHealthyBackup() throws Exception {
        Path lastGood = owned("echo-2026-09-01.sqlite", 1_000);
        Path corrupt = backups.resolve("echo-2026-09-02.sqlite");
        Files.writeString(corrupt, "not a database");
        Files.setLastModifiedTime(corrupt, FileTime.fromMillis(2_000));

        service.housekeeping();

        assertThat(lastGood).exists();
        assertThat(SqliteStartupRecovery.isHealthyDatabase(lastGood)).isTrue();
        assertThat(corrupt).doesNotExist();
    }

    @Test
    void unverifiableBackupsArePreservedUntilAHealthyCopyExists() throws Exception {
        Path corrupt = backups.resolve("echo-2026-09-01.sqlite");
        Files.writeString(corrupt, "recovery material");

        service.housekeeping();
        assertThat(corrupt).exists();

        String fresh = service.backup("test");
        assertThat(SqliteStartupRecovery.isHealthyDatabase(backups.resolve(fresh))).isTrue();
        assertThat(corrupt).doesNotExist();
    }

    @Test
    void capacityLimitRemovesOldestFilesEvenWithinTheRetentionPeriod() throws Exception {
        Path oldest = owned("echo-2026-10-16.sqlite", 1_000);
        Path middle = owned("echo-2026-10-17.sqlite", 2_000);
        Path newest = owned("echo-2026-10-18.sqlite", 3_000);
        ReflectionTestUtils.setField(service, "maxTotalBytes", Files.size(database) * 2);

        service.housekeeping();

        assertThat(oldest).doesNotExist();
        assertThat(middle).exists();
        assertThat(newest).exists();
    }

    @Test
    void fileCountLimitAlsoBoundsManySmallBackups() throws Exception {
        Path oldest = owned("echo-2026-10-16.sqlite", 1_000);
        Path middle = owned("echo-2026-10-17.sqlite", 2_000);
        Path newest = owned("echo-2026-10-18.sqlite", 3_000);
        ReflectionTestUtils.setField(service, "maxFiles", 2);

        service.housekeeping();

        assertThat(oldest).doesNotExist();
        assertThat(middle).exists();
        assertThat(newest).exists();
    }

    @Test
    void refusingANewBackupNeverDeletesTheLastVerifiedCopy() throws Exception {
        Path lastGood = owned("echo-2026-09-01.sqlite", 1_000);
        ReflectionTestUtils.setField(service, "maxTotalBytes", Files.size(lastGood));

        assertThatThrownBy(() -> service.backup("test"))
                .hasMessageContaining("preserving the last verified backup");

        assertThat(lastGood).exists();
        assertThat(SqliteStartupRecovery.isHealthyDatabase(lastGood)).isTrue();
        assertNoPartialFiles();
    }

    @Test
    void aSingleRetainedFileStillAllowsPublishingItsVerifiedReplacement() {
        ReflectionTestUtils.setField(service, "maxFiles", 1);
        String first = service.backup("first");
        String second = service.backup("second");

        assertThat(backups.resolve(first)).doesNotExist();
        assertThat(SqliteStartupRecovery.isHealthyDatabase(backups.resolve(second))).isTrue();
        assertThat(service.listBackups()).hasSize(1);
    }

    @Test
    void diskSpaceIsCheckedBeforeCopyingAndBackupWorksAfterSpaceReturns() throws Exception {
        AtomicLong free = new AtomicLong(0);
        service = service(source, dir -> free.get());

        assertThatThrownBy(() -> service.backup("test"))
                .hasMessageContaining("Insufficient backup disk space");
        assertThat(service.listBackups()).isEmpty();
        assertNoPartialFiles();

        free.set(Long.MAX_VALUE);
        String filename = service.backup("test");
        assertThat(SqliteStartupRecovery.isHealthyDatabase(backups.resolve(filename))).isTrue();
        assertNoPartialFiles();
    }

    @Test
    void snapshotLargerThanTheCapacityIsRejectedBeforeCreatingAPartialFile() {
        ReflectionTestUtils.setField(service, "maxTotalBytes", 1L);

        assertThatThrownBy(() -> service.backup("test"))
                .hasMessageContaining("snapshot exceeds echo.backup.max-total-bytes");
        assertThat(service.listBackups()).isEmpty();
    }

    @Test
    void cleanupLeavesUnrelatedFilesDirectoriesAndInvalidDatesAlone() throws Exception {
        Path unrelated = backups.resolve("manual.sqlite");
        Path invalid = backups.resolve("echo-2026-99-99.sqlite");
        Path subdirectory = Files.createDirectories(backups.resolve("echo-2026-09-01.sqlite"));
        Files.writeString(unrelated, "unrelated");
        Files.writeString(invalid, "invalid date");
        owned("echo-2026-10-18.sqlite", 3_000);

        service.housekeeping();

        assertThat(unrelated).hasContent("unrelated");
        assertThat(invalid).hasContent("invalid date");
        assertThat(subdirectory).isDirectory();
    }

    @Test
    void housekeepingNeverFollowsOrDeletesBackupNamedSymlinks() throws Exception {
        Path link = backups.resolve("echo-2026-09-01.sqlite");
        try {
            Files.createSymbolicLink(link, database);
        } catch (UnsupportedOperationException | FileSystemException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
        }
        owned("echo-2026-10-18.sqlite", 3_000);

        service.housekeeping();

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(SqliteStartupRecovery.isHealthyDatabase(database)).isTrue();
    }

    @Test
    void concurrentBackupIsRejectedAndHousekeepingSkipsAnActiveBackup() throws Exception {
        CountDownLatch copying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        service = service(source, dir -> {
            copying.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("test release timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return Long.MAX_VALUE;
        });
        var worker = Executors.newSingleThreadExecutor();
        try {
            var first = worker.submit(() -> service.backup("first"));
            assertThat(copying.await(3, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.backup("second"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("already running");
            service.housekeeping();
            assertThat(service.listBackups()).isEmpty();

            release.countDown();
            assertThat(SqliteStartupRecovery.isHealthyDatabase(backups.resolve(first.get(3, TimeUnit.SECONDS))))
                    .isTrue();
            assertThat(service.listBackups()).hasSize(1);
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invalidLimitsFailAtStartup() {
        ReflectionTestUtils.setField(service, "maxFiles", 0);
        assertThatThrownBy(service::validateSettings).isInstanceOf(IllegalArgumentException.class);
    }

    private SqliteBackupService service(DataSource dataSource, SqliteBackupService.DiskSpace diskSpace) {
        var result = new SqliteBackupService(dataSource, CLOCK, diskSpace);
        ReflectionTestUtils.setField(result, "backupPath", backups.toString());
        ReflectionTestUtils.setField(result, "retentionDays", 7);
        result.validateSettings();
        return result;
    }

    private Path owned(String name, long modifiedMillis) throws Exception {
        Path copy = backups.resolve(name);
        Files.copy(database, copy);
        Files.setLastModifiedTime(copy, FileTime.fromMillis(modifiedMillis));
        return copy;
    }

    private void assertNoPartialFiles() throws Exception {
        try (var files = Files.list(backups)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.endsWith(".part"));
        }
    }
}
