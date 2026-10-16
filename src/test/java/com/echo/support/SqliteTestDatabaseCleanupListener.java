package com.echo.support;

import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.nio.file.Files;
import java.nio.file.Path;

/** Closes the Spring-managed connection pool before deleting a test's SQLite files. */
public final class SqliteTestDatabaseCleanupListener extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        // afterTestClass callbacks run in reverse order; clean up after all other listeners.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void afterTestClass(TestContext testContext) throws Exception {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        String url = testContext.getApplicationContext().getEnvironment()
                .getRequiredProperty("spring.datasource.url");
        if (!url.startsWith("jdbc:sqlite:")) {
            throw new IllegalStateException("SQLite cleanup requires a SQLite datasource");
        }
        String filename = url.substring("jdbc:sqlite:".length()).split("\\?", 2)[0];
        Path database = Path.of(filename);
        if (!database.isAbsolute()) {
            throw new IllegalStateException("SQLite cleanup requires an absolute test database path");
        }

        // Evict and close through Spring's context cache, including the connection pool.
        // Windows cannot delete an open database or its WAL files.
        testContext.markApplicationContextDirty(DirtiesContext.HierarchyMode.CURRENT_LEVEL);
        Files.deleteIfExists(Path.of(filename + "-wal"));
        Files.deleteIfExists(Path.of(filename + "-shm"));
        Files.deleteIfExists(database);
    }
}
