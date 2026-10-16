package com.echo.service;

import com.echo.config.SqliteRecoveryEnvironmentPostProcessor;
import com.echo.config.SqliteStartupRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Exits a SQLite process only after confirmed database corruption.
 *
 * <p>Locks and temporary connection failures remain recoverable and never
 * terminate the process. A confirmed corrupt process exits so its supervisor
 * can restart it and the startup recovery policy can run before DataSource
 * creation.</p>
 */
@Component
@ConditionalOnExpression("'${spring.datasource.url:}'.startsWith('jdbc:sqlite:')")
@ConditionalOnProperty(name = "echo.sqlite.recovery.runtime-check-enabled",
        havingValue = "true", matchIfMissing = true)
public class SqliteRuntimeHealthGuard {

    static final int CORRUPT_DATABASE_EXIT_CODE = 70;
    private static final Logger log = LoggerFactory.getLogger(SqliteRuntimeHealthGuard.class);

    private final Path database;
    private final Runnable corruptionTerminator;

    @Autowired
    public SqliteRuntimeHealthGuard(@Value("${spring.datasource.url}") String jdbcUrl) {
        this(SqliteRecoveryEnvironmentPostProcessor.databasePath(jdbcUrl)
                        .orElseThrow(() -> new IllegalStateException(
                                "SQLite runtime guard requires a file-backed database.")),
                () -> Runtime.getRuntime().halt(CORRUPT_DATABASE_EXIT_CODE));
    }

    SqliteRuntimeHealthGuard(Path database, Runnable corruptionTerminator) {
        this.database = database;
        this.corruptionTerminator = corruptionTerminator;
    }

    @Scheduled(
            initialDelayString = "${echo.sqlite.recovery.runtime-check-interval-ms:30000}",
            fixedDelayString = "${echo.sqlite.recovery.runtime-check-interval-ms:30000}")
    void checkNow() {
        SqliteStartupRecovery.DatabaseState state =
                SqliteStartupRecovery.inspectDatabaseQuick(database);
        if (state == SqliteStartupRecovery.DatabaseState.CORRUPT) {
            terminateForCorruption();
        } else if (state == SqliteStartupRecovery.DatabaseState.UNAVAILABLE) {
            log.warn("SQLite runtime health check is temporarily unavailable; retrying later");
        }
    }

    private void terminateForCorruption() {
        log.error("SQLite corruption detected at runtime; terminating Echo for supervised recovery");
        corruptionTerminator.run();
    }
}
