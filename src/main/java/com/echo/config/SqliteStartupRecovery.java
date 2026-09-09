package com.echo.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Verifies a file-backed SQLite database before Spring creates the DataSource.
 *
 * <p>Normal WAL crash recovery is left to SQLite. This class only handles a
 * database that SQLite itself reports as corrupt. Transient failures such as a
 * lock or an unreadable path always fail closed and never trigger a restore.</p>
 */
public final class SqliteStartupRecovery {

    private static final Pattern BACKUP_NAME = Pattern.compile(
            "echo-\\d{4}-\\d{2}-\\d{2}(?:-\\d{6}-\\d{3}-[0-9a-f]{8})?\\.sqlite");
    private static final DateTimeFormatter QUARANTINE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final int SQLITE_CORRUPT = 11;
    private static final int SQLITE_NOTADB = 26;

    private final Clock clock;

    public SqliteStartupRecovery() {
        this(Clock.systemUTC());
    }

    SqliteStartupRecovery(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RecoveryResult verifyOrRecover(Path database, Path backupDirectory, RecoveryMode mode) {
        Path normalizedDatabase = database.toAbsolutePath().normalize();
        if (!Files.exists(normalizedDatabase)) {
            return new RecoveryResult(Status.NEW_DATABASE, null, null, null);
        }

        Verification live = verify(normalizedDatabase);
        if (live.healthy()) {
            return new RecoveryResult(Status.HEALTHY, null, null, null);
        }
        if (!live.corrupt()) {
            throw failure("SQLite database is unavailable; automatic restore was not attempted.");
        }
        if (mode == RecoveryMode.FAIL_FAST) {
            throw failure("SQLite database integrity check failed; recovery mode is fail-fast.");
        }

        return restoreLatest(normalizedDatabase, backupDirectory.toAbsolutePath().normalize());
    }

    private RecoveryResult restoreLatest(Path database, Path backupDirectory) {
        Path backup = newestHealthyBackup(backupDirectory);
        if (backup == null) {
            throw failure("SQLite database is corrupt and no healthy backup is available.");
        }

        String token = QUARANTINE_TIME.format(clock.instant()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path quarantine = database.resolveSibling(database.getFileName() + ".corrupt-" + token);
        Path staged = database.resolveSibling(database.getFileName() + ".restore-" + token + ".tmp");

        try {
            Files.copy(backup, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Verification stagedVerification = verify(staged);
            if (!stagedVerification.healthy()) {
                throw failure("The selected SQLite backup failed staged verification.");
            }

            moveIfExists(database, quarantine);
            moveIfExists(sidecar(database, "-wal"), sidecar(quarantine, "-wal"));
            moveIfExists(sidecar(database, "-shm"), sidecar(quarantine, "-shm"));
            move(staged, database);

            Verification restoredVerification = verify(database);
            if (!restoredVerification.healthy()) {
                rollbackRestore(database, quarantine);
                throw failure("SQLite backup restore verification failed; the corrupt database was preserved.");
            }

            Path receipt = writeReceipt(backupDirectory, token, database, backup, quarantine);
            return new RecoveryResult(Status.RESTORED, backup, quarantine, receipt);
        } catch (IOException ex) {
            try {
                rollbackRestore(database, quarantine);
            } catch (RuntimeException ignored) {
                // The original exception remains the actionable startup failure.
            }
            throw failure("SQLite recovery could not complete filesystem operations.");
        } finally {
            deleteQuietly(staged);
            deleteQuietly(sidecar(staged, "-wal"));
            deleteQuietly(sidecar(staged, "-shm"));
        }
    }

    private Path newestHealthyBackup(Path backupDirectory) {
        if (!Files.isDirectory(backupDirectory)) {
            return null;
        }
        try (var files = Files.list(backupDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        Path name = path.getFileName();
                        return name != null && BACKUP_NAME.matcher(name.toString()).matches();
                    })
                    .sorted(Comparator.comparingLong(SqliteStartupRecovery::lastModified).reversed())
                    .filter(path -> verify(path).healthy())
                    .findFirst()
                    .orElse(null);
        } catch (IOException ex) {
            throw failure("SQLite backup directory could not be read.");
        }
    }

    static Verification verify(Path database) {
        return verify(database, false);
    }

    private static Verification verify(Path database, boolean quick) {
        if (!Files.isRegularFile(database)) {
            return new Verification(false, false);
        }
        try {
            if (Files.size(database) == 0) {
                return new Verification(false, true);
            }
        } catch (IOException ex) {
            return new Verification(false, false);
        }

        String path = database.toAbsolutePath().normalize().toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + path + "?busy_timeout=1000&foreign_keys=ON");
             Statement statement = connection.createStatement()) {
            List<String> integrityRows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(
                    quick ? "PRAGMA quick_check" : "PRAGMA integrity_check")) {
                while (result.next()) {
                    integrityRows.add(result.getString(1));
                }
            }
            if (integrityRows.size() != 1 || !"ok".equalsIgnoreCase(integrityRows.get(0))) {
                return new Verification(false, true);
            }
            if (!quick) {
                try (ResultSet foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (foreignKeys.next()) {
                        return new Verification(false, true);
                    }
                }
            }
            return new Verification(true, false);
        } catch (SQLException ex) {
            return new Verification(false, isCorruption(ex));
        }
    }

    /** Returns true only when SQLite integrity and foreign-key checks both pass. */
    public static boolean isHealthyDatabase(Path database) {
        return verify(database.toAbsolutePath().normalize()).healthy();
    }

    /** Inspects an existing database without treating a transient failure as corruption. */
    public static DatabaseState inspectDatabase(Path database) {
        Verification verification = verify(database.toAbsolutePath().normalize());
        return databaseState(verification);
    }

    /** Uses SQLite's bounded quick check for periodic runtime monitoring. */
    public static DatabaseState inspectDatabaseQuick(Path database) {
        Verification verification = verify(database.toAbsolutePath().normalize(), true);
        return databaseState(verification);
    }

    private static DatabaseState databaseState(Verification verification) {
        if (verification.healthy()) {
            return DatabaseState.HEALTHY;
        }
        return verification.corrupt() ? DatabaseState.CORRUPT : DatabaseState.UNAVAILABLE;
    }

    public static boolean isCorruption(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                SQLException chained = sqlException;
                while (chained != null) {
                    if (chained.getErrorCode() == SQLITE_CORRUPT
                            || chained.getErrorCode() == SQLITE_NOTADB
                            || containsCorruptionMarker(chained.getMessage())) {
                        return true;
                    }
                    chained = chained.getNextException();
                }
            } else if (containsCorruptionMarker(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsCorruptionMarker(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("database disk image is malformed")
                || normalized.contains("file is not a database");
    }

    private Path writeReceipt(Path backupDirectory, String token, Path database,
                              Path backup, Path quarantine) throws IOException {
        Files.createDirectories(backupDirectory);
        Path receipt = backupDirectory.resolve("sqlite-recovery-" + token + ".properties");
        String body = "recoveredAt=" + Instant.now(clock) + System.lineSeparator()
                + "database=" + database.getFileName() + System.lineSeparator()
                + "backup=" + backup.getFileName() + System.lineSeparator()
                + "quarantine=" + quarantine.getFileName() + System.lineSeparator();
        Files.writeString(receipt, body);
        return receipt;
    }

    private static void rollbackRestore(Path database, Path quarantine) {
        try {
            if (Files.exists(quarantine)) {
                Path failed = database.resolveSibling(database.getFileName() + ".failed-restore-"
                        + UUID.randomUUID().toString().substring(0, 8));
                moveIfExists(database, failed);
                moveIfExists(sidecar(database, "-wal"), sidecar(failed, "-wal"));
                moveIfExists(sidecar(database, "-shm"), sidecar(failed, "-shm"));
                move(quarantine, database);
                moveIfExists(sidecar(quarantine, "-wal"), sidecar(database, "-wal"));
                moveIfExists(sidecar(quarantine, "-shm"), sidecar(database, "-shm"));
            }
        } catch (IOException ex) {
            throw failure("SQLite recovery rollback failed; manual recovery is required.");
        }
    }

    private static void moveIfExists(Path source, Path target) throws IOException {
        if (Files.exists(source)) {
            move(source, target);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    private static Path sidecar(Path database, String suffix) {
        return database.resolveSibling(database.getFileName() + suffix);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return Long.MIN_VALUE;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Startup will still fail safely if a temporary file cannot be removed.
        }
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }

    public enum RecoveryMode {
        FAIL_FAST,
        RESTORE_LATEST;

        public static RecoveryMode parse(String value) {
            if (value == null || value.isBlank()) {
                return FAIL_FAST;
            }
            String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException ex) {
                throw failure("Unsupported SQLite recovery mode. Use fail-fast or restore-latest.");
            }
        }
    }

    public enum Status {
        NEW_DATABASE,
        HEALTHY,
        RESTORED
    }

    public enum DatabaseState {
        HEALTHY,
        CORRUPT,
        UNAVAILABLE
    }

    public record RecoveryResult(Status status, Path backup, Path quarantine, Path receipt) {}

    record Verification(boolean healthy, boolean corrupt) {}
}
