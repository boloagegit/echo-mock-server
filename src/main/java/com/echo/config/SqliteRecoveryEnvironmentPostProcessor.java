package com.echo.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Runs SQLite verification and optional restore before DataSource creation whenever the
 * datasource is a file-backed SQLite database (the default), not only under the sqlite profile.
 */
public final class SqliteRecoveryEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    private final Log log;

    public SqliteRecoveryEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SqliteRecoveryEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        String jdbcUrl = environment.getProperty("spring.datasource.url");
        Optional<Path> database = databasePath(jdbcUrl);
        if (database.isEmpty()) {
            // 非 SQLite 或記憶體資料庫（測試）不需要復原；明確指定 sqlite profile 時仍要求檔案資料庫
            if (environment.acceptsProfiles(Profiles.of("sqlite"))) {
                throw new IllegalStateException(
                        "SQLite recovery requires a file-backed spring.datasource.url.");
            }
            return;
        }
        createParentDirectory(database.get());

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

    /** SQLite JDBC 不會自動建立目錄；預設資料檔位於 ./data。 */
    private static void createParentDirectory(Path database) {
        Path parent = database.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the SQLite database directory.", e);
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
            if (configured.substring(query).toLowerCase(Locale.ROOT).contains("mode=memory")) {
                return Optional.empty();
            }
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
