package com.echo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyH2GuardTest {

    private static final String DEFAULT_URL = "jdbc:sqlite:./data/mockdb.sqlite?journal_mode=WAL";

    @TempDir
    Path workDir;

    @Test
    void rejectsH2ProfileWithMigrationHint() {
        assertThatThrownBy(() -> LegacyH2Guard.check(true, DEFAULT_URL, false, workDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'h2' profile is no longer supported")
                .hasMessageContaining("migrate-h2-to-sqlite.py");
    }

    @Test
    void rejectsH2JdbcUrl() {
        assertThatThrownBy(() -> LegacyH2Guard.check(false, "jdbc:h2:file:./mockdb", false, workDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("H2 JDBC URLs are no longer supported");
        assertThatThrownBy(() -> LegacyH2Guard.check(false, "JDBC:H2:mem:x", true, workDir))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartEmptyWhenLegacyH2DataWasNotMigrated() throws Exception {
        Files.writeString(workDir.resolve(LegacyH2Guard.LEGACY_H2_FILE), "legacy");

        assertThatThrownBy(() -> LegacyH2Guard.check(false, DEFAULT_URL, false, workDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to start with an empty database")
                .hasMessageContaining(LegacyH2Guard.IGNORE_PROPERTY);
    }

    @Test
    void startsWhenMigratedOrExplicitlyIgnoredOrNoLegacyData() throws Exception {
        assertThatCode(() -> LegacyH2Guard.check(false, DEFAULT_URL, false, workDir)).doesNotThrowAnyException();

        Files.writeString(workDir.resolve(LegacyH2Guard.LEGACY_H2_FILE), "legacy");
        assertThatCode(() -> LegacyH2Guard.check(false, DEFAULT_URL, true, workDir)).doesNotThrowAnyException();
        assertThatCode(() -> LegacyH2Guard.check(false, "jdbc:postgresql://db/echo", false, workDir))
                .doesNotThrowAnyException();

        Files.createDirectories(workDir.resolve("data"));
        Files.writeString(workDir.resolve("data/mockdb.sqlite"), "");
        assertThatCode(() -> LegacyH2Guard.check(false, DEFAULT_URL, false, workDir)).doesNotThrowAnyException();
    }
}
