package com.echo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteStartupRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void missingDatabaseIsLeftForHibernateToCreate() {
        Path database = tempDir.resolve("new.sqlite");

        var result = recovery().verifyOrRecover(database, tempDir.resolve("backups"),
                SqliteStartupRecovery.RecoveryMode.FAIL_FAST);

        assertThat(result.status()).isEqualTo(SqliteStartupRecovery.Status.NEW_DATABASE);
        assertThat(database).doesNotExist();
    }

    @Test
    void healthyDatabasePassesWithoutCreatingRecoveryFiles() throws Exception {
        Path database = tempDir.resolve("healthy.sqlite");
        createDatabase(database, "live");

        var result = recovery().verifyOrRecover(database, tempDir.resolve("backups"),
                SqliteStartupRecovery.RecoveryMode.FAIL_FAST);

        assertThat(result.status()).isEqualTo(SqliteStartupRecovery.Status.HEALTHY);
        assertThat(readValue(database)).isEqualTo("live");
    }

    @Test
    void failFastPreservesCorruptDatabase() throws Exception {
        Path database = tempDir.resolve("corrupt.sqlite");
        Files.writeString(database, "not a sqlite database");

        assertThatThrownBy(() -> recovery().verifyOrRecover(
                database, tempDir.resolve("backups"), SqliteStartupRecovery.RecoveryMode.FAIL_FAST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SQLite database integrity check failed; recovery mode is fail-fast.");

        assertThat(Files.readString(database)).isEqualTo("not a sqlite database");
    }

    @Test
    void restoreLatestSkipsBadBackupAndQuarantinesLiveDatabase() throws Exception {
        Path database = tempDir.resolve("mockdb.sqlite");
        Files.writeString(database, "broken live database");
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Path healthy = backups.resolve("echo-2026-09-08.sqlite");
        createDatabase(healthy, "healthy-backup");
        Files.setLastModifiedTime(healthy, FileTime.fromMillis(1_000));
        Path newerButBroken = backups.resolve("echo-2026-09-09.sqlite");
        Files.writeString(newerButBroken, "broken backup");
        Files.setLastModifiedTime(newerButBroken, FileTime.fromMillis(2_000));

        var result = recovery().verifyOrRecover(
                database, backups, SqliteStartupRecovery.RecoveryMode.RESTORE_LATEST);

        assertThat(result.status()).isEqualTo(SqliteStartupRecovery.Status.RESTORED);
        assertThat(result.backup()).isEqualTo(healthy);
        assertThat(readValue(database)).isEqualTo("healthy-backup");
        assertThat(result.quarantine()).exists();
        assertThat(Files.readString(result.quarantine())).isEqualTo("broken live database");
        assertThat(result.receipt()).exists();
        assertThat(Files.readString(result.receipt()))
                .contains("backup=echo-2026-09-08.sqlite")
                .contains("quarantine=" + result.quarantine().getFileName());
    }

    @Test
    void restoreLatestFailsClosedWhenNoHealthyBackupExists() throws Exception {
        Path database = tempDir.resolve("mockdb.sqlite");
        Files.writeString(database, "broken live database");
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        Files.writeString(backups.resolve("echo-2026-09-09.sqlite"), "broken backup");

        assertThatThrownBy(() -> recovery().verifyOrRecover(
                database, backups, SqliteStartupRecovery.RecoveryMode.RESTORE_LATEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SQLite database is corrupt and no healthy backup is available.");

        assertThat(Files.readString(database)).isEqualTo("broken live database");
    }

    @Test
    void restoreLatestDoesNotReplaceAnUnavailableNonDatabasePath() throws Exception {
        Path database = Files.createDirectory(tempDir.resolve("mockdb.sqlite"));
        Path backups = Files.createDirectories(tempDir.resolve("backups"));
        createDatabase(backups.resolve("echo-2026-09-09.sqlite"), "backup");

        assertThatThrownBy(() -> recovery().verifyOrRecover(
                database, backups, SqliteStartupRecovery.RecoveryMode.RESTORE_LATEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SQLite database is unavailable; automatic restore was not attempted.");

        assertThat(database).isDirectory();
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith("mockdb.sqlite.corrupt-"));
        }
    }

    @Test
    void foreignKeyViolationFailsIntegrityGate() throws Exception {
        Path database = tempDir.resolve("foreign-key.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE child (parent_id INTEGER REFERENCES parent(id))");
            statement.execute("INSERT INTO child(parent_id) VALUES (99)");
        }

        assertThatThrownBy(() -> recovery().verifyOrRecover(
                database, tempDir.resolve("backups"), SqliteStartupRecovery.RecoveryMode.FAIL_FAST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SQLite database integrity check failed; recovery mode is fail-fast.");
    }

    @Test
    void parsesRecoveryModesWithoutUnsafeFallback() {
        assertThat(SqliteStartupRecovery.RecoveryMode.parse(null))
                .isEqualTo(SqliteStartupRecovery.RecoveryMode.FAIL_FAST);
        assertThat(SqliteStartupRecovery.RecoveryMode.parse("restore-latest"))
                .isEqualTo(SqliteStartupRecovery.RecoveryMode.RESTORE_LATEST);
        assertThatThrownBy(() -> SqliteStartupRecovery.RecoveryMode.parse("ignore"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported SQLite recovery mode");
    }

    @Test
    void extractsOnlyFileBackedSqlitePaths() {
        assertThat(SqliteRecoveryEnvironmentPostProcessor.databasePath(
                "jdbc:sqlite:./data/mock.sqlite?journal_mode=WAL"))
                .contains(Path.of("./data/mock.sqlite"));
        assertThat(SqliteRecoveryEnvironmentPostProcessor.databasePath(
                "jdbc:sqlite:file:./data/mock.sqlite?mode=rwc"))
                .contains(Path.of("./data/mock.sqlite"));
        assertThat(SqliteRecoveryEnvironmentPostProcessor.databasePath("jdbc:sqlite::memory:"))
                .isEmpty();
        assertThat(SqliteRecoveryEnvironmentPostProcessor.databasePath("jdbc:h2:file:./mockdb"))
                .isEmpty();
    }

    private SqliteStartupRecovery recovery() {
        return new SqliteStartupRecovery(Clock.fixed(
                Instant.parse("2026-09-09T12:34:56.789Z"), ZoneOffset.UTC));
    }

    private static void createDatabase(Path path, String value) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (value TEXT NOT NULL)");
            try (var prepared = connection.prepareStatement("INSERT INTO marker(value) VALUES (?)")) {
                prepared.setString(1, value);
                prepared.executeUpdate();
            }
        }
    }

    private static String readValue(Path path) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT value FROM marker")) {
            return result.next() ? result.getString(1) : null;
        }
    }
}
