package com.echo.service;

import com.echo.config.SqliteStartupRecovery;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteBackupServiceTest {

    @TempDir
    Path tempDir;

    private Path backupDirectory;
    private SqliteBackupService backupService;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        Path database = tempDir.resolve("live.sqlite");
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + database.toAbsolutePath());
        dataSource.setMaximumPoolSize(1);
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

    @AfterEach
    void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
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
    void sqliteBackupSupportsPathsWithSpacesAndApostrophes() throws Exception {
        backupDirectory = tempDir.resolve("Echo User's Data");
        setField("backupPath", backupDirectory.toString());
        String filename = backupService.backup("test");
        Path backup = backupDirectory.resolve(filename);
        assertThat(SqliteStartupRecovery.isHealthyDatabase(backup)).isTrue();
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + backup.toAbsolutePath());
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT value FROM marker")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("committed");
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void backupCompletesWithAConsistentSnapshotWhileWalWritesContinue() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE first_counter (value INTEGER NOT NULL)");
            statement.execute("INSERT INTO first_counter VALUES (0)");
            statement.execute("CREATE TABLE bulk (id INTEGER PRIMARY KEY, payload BLOB NOT NULL)");
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO bulk VALUES (?, zeroblob(65536))")) {
                for (int id = 0; id < 256; id++) {
                    insert.setInt(1, id);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            statement.execute("CREATE TABLE last_counter (value INTEGER NOT NULL)");
            statement.execute("INSERT INTO last_counter VALUES (0)");
            connection.commit();
            connection.setAutoCommit(true);
        }
        var writing = new AtomicBoolean(true);
        var commits = new AtomicInteger();
        var started = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        String filename;
        try {
            var writer = executor.submit(() -> {
                try (var connection = DriverManager.getConnection(dataSource.getJdbcUrl());
                     var statement = connection.createStatement()) {
                    statement.execute("PRAGMA busy_timeout=5000");
                    statement.execute("PRAGMA synchronous=NORMAL");
                    connection.setAutoCommit(false);
                    while (writing.get() && !Thread.currentThread().isInterrupted()) {
                        statement.executeUpdate("UPDATE first_counter SET value = value + 1");
                        statement.executeUpdate("UPDATE last_counter SET value = value + 1");
                        connection.commit();
                        commits.incrementAndGet();
                        started.countDown();
                    }
                }
                return null;
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            int beforeBackup = commits.get();
            var snapshot = executor.submit(() -> backupService.backup("continuous-writes"));
            filename = snapshot.get(15, TimeUnit.SECONDS);
            assertThat(writer.isDone()).as("Writes must continue until backup completes").isFalse();
            assertThat(commits.get()).isGreaterThan(beforeBackup);
            writing.set(false);
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            writing.set(false);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        Path snapshot = backupDirectory.resolve(filename);
        assertThat(SqliteStartupRecovery.isHealthyDatabase(snapshot)).isTrue();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot);
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT f.value, l.value FROM first_counter f, last_counter l")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isPositive().isEqualTo(rows.getInt(2)).isLessThanOrEqualTo(commits.get());
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*), SUM(LENGTH(payload)) FROM bulk")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(256);
                assertThat(rows.getLong(2)).isEqualTo(256L * 65536);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backupReleasesSnapshotAndRestoresPooledConnectionEvenOnFailure(boolean rejectBackup) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.unwrap(SQLiteConnection.class).getConnectionConfig()
                    .setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        }
        if (rejectBackup) {
            setField("minFreeBytes", Long.MAX_VALUE);
            assertThatThrownBy(() -> backupService.backup("rejected"))
                    .hasMessageContaining("Insufficient backup disk space");
        } else {
            backupService.backup("successful");
        }
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            assertThat(connection.unwrap(SQLiteConnection.class).getConnectionConfig().getTransactionMode())
                    .isEqualTo(SQLiteConfig.TransactionMode.IMMEDIATE);
            // A returned connection must remain writable, not retain the snapshot transaction.
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO marker(value) VALUES ('after-backup')");
            }
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

    private void setField(String name, Object value) throws Exception {
        var field = SqliteBackupService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(backupService, value);
    }
}
