package com.echo.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.nio.file.Path;
import java.util.Optional;

/** Runs SQLite verification and optional restore before DataSource creation. */
public final class SqliteRecoveryEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    private final Log log;

    public SqliteRecoveryEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SqliteRecoveryEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("sqlite"))) {
            return;
        }
        String jdbcUrl = environment.getProperty("spring.datasource.url");
        Optional<Path> database = databasePath(jdbcUrl);
        if (database.isEmpty()) {
            throw new IllegalStateException(
                    "SQLite recovery requires a file-backed spring.datasource.url.");
        }

        Path backupDirectory = Path.of(environment.getProperty("echo.backup.path", "./backups"));
        SqliteStartupRecovery.RecoveryMode mode = SqliteStartupRecovery.RecoveryMode.parse(
                environment.getProperty("echo.sqlite.recovery.mode", "fail-fast"));
        SqliteStartupRecovery.RecoveryResult result = new SqliteStartupRecovery()
                .verifyOrRecover(database.get(), backupDirectory, mode);
        if (result.status() == SqliteStartupRecovery.Status.RESTORED) {
            log.warn("SQLite database restored from verified backup; corrupt files were quarantined at "
                    + result.quarantine());
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    public static Optional<Path> databasePath(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.regionMatches(true, 0, "jdbc:sqlite:", 0, 12)) {
            return Optional.empty();
        }
        String configured = jdbcUrl.substring(12);
        int query = configured.indexOf('?');
        if (query >= 0) {
            configured = configured.substring(0, query);
        }
        if (configured.regionMatches(true, 0, "file:", 0, 5)) {
            configured = configured.substring(5);
        }
        if (configured.isBlank() || ":memory:".equalsIgnoreCase(configured)) {
            return Optional.empty();
        }
        return Optional.of(Path.of(configured));
    }
}
