package com.echo.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * H2 已不再支援。在建立 DataSource 之前：
 * <ul>
 *   <li>拒絕 {@code h2} profile 與 {@code jdbc:h2:} URL，並說明如何轉換資料；</li>
 *   <li>工作目錄仍有舊版預設的 H2 資料檔（{@code mockdb.mv.db}），而 SQLite 資料庫尚未建立時拒絕啟動，
 *       避免升級後以空資料庫啟動、看起來像規則全部消失。確認不需要舊資料時，可設定
 *       {@code echo.database.ignore-legacy-h2=true} 略過。</li>
 * </ul>
 */
public final class LegacyH2Guard implements EnvironmentPostProcessor, Ordered {

    static final String IGNORE_PROPERTY = "echo.database.ignore-legacy-h2";
    /** 舊版預設 URL {@code jdbc:h2:file:./mockdb} 產生的資料檔。 */
    static final String LEGACY_H2_FILE = "mockdb.mv.db";

    private static final String MIGRATION_HINT =
            "Convert existing data with scripts/migrate-h2-to-sqlite.py "
                    + "(see README: \"Migrating from H2\").";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        check(environment.acceptsProfiles(Profiles.of("h2")),
                environment.getProperty("spring.datasource.url"),
                environment.getProperty(IGNORE_PROPERTY, Boolean.class, false),
                Path.of(""));
    }

    /** 在 SQLite 啟動復原之前執行，確保不會先替舊環境建立一個空的 SQLite 檔。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    /**
     * @param workingDirectory 解析相對路徑的目錄（正式執行時為目前工作目錄）
     * @throws IllegalStateException 設定仍指向 H2，或偵測到尚未轉換的舊 H2 資料
     */
    static void check(boolean h2ProfileActive, String jdbcUrl, boolean ignoreLegacyData, Path workingDirectory) {
        if (h2ProfileActive) {
            throw new IllegalStateException(
                    "The 'h2' profile is no longer supported; SQLite is now the default database. "
                            + MIGRATION_HINT);
        }
        if (jdbcUrl != null && jdbcUrl.regionMatches(true, 0, "jdbc:h2:", 0, 8)) {
            throw new IllegalStateException(
                    "H2 JDBC URLs are no longer supported; use SQLite (default) or an external database. "
                            + MIGRATION_HINT);
        }
        if (ignoreLegacyData) {
            return;
        }
        Optional<Path> sqlite = SqliteRecoveryEnvironmentPostProcessor.databasePath(jdbcUrl);
        Path legacy = workingDirectory.resolve(LEGACY_H2_FILE);
        if (sqlite.isPresent() && Files.exists(legacy) && !Files.exists(workingDirectory.resolve(sqlite.get()))) {
            throw new IllegalStateException(
                    "Found legacy H2 data (" + LEGACY_H2_FILE + ") but no SQLite database yet; refusing to start "
                            + "with an empty database. " + MIGRATION_HINT + " To start empty anyway, set "
                            + IGNORE_PROPERTY + "=true.");
        }
    }
}
