package com.echo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteRuntimeHealthGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void healthyDatabaseDoesNotTerminateProcess() throws Exception {
        AtomicInteger terminations = new AtomicInteger();
        Path database = tempDir.resolve("healthy.sqlite");
        createDatabase(database);

        new SqliteRuntimeHealthGuard(database, terminations::incrementAndGet).checkNow();

        assertThat(terminations).hasValue(0);
    }

    @Test
    void failedQuickCheckTerminatesProcess() throws Exception {
        AtomicInteger terminations = new AtomicInteger();
        Path database = tempDir.resolve("corrupt.sqlite");
        Files.writeString(database, "not a database");

        new SqliteRuntimeHealthGuard(database, terminations::incrementAndGet).checkNow();

        assertThat(terminations).hasValue(1);
    }

    @Test
    void unavailablePathDoesNotTerminateProcess() throws Exception {
        AtomicInteger terminations = new AtomicInteger();
        Path database = Files.createDirectory(tempDir.resolve("unavailable.sqlite"));

        new SqliteRuntimeHealthGuard(database, terminations::incrementAndGet).checkNow();

        assertThat(terminations).hasValue(0);
    }

    private static void createDatabase(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker (id INTEGER PRIMARY KEY)");
        }
    }
}
