package com.echo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class BuildInfoServiceTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final Instant BUILD_TIME = Instant.parse("2026-10-19T08:00:00Z");

    @Test
    void exposesPackagedIdentityAndLogsItAtStartup(CapturedOutput output) {
        BuildInfoService service = new BuildInfoService(Optional.of(properties(COMMIT, "false")));

        assertThat(service.getMetadata())
                .isEqualTo(new BuildInfoService.Metadata(COMMIT, "0123456789ab", false, BUILD_TIME));
        service.logStartupVersion();
        assertThat(output).contains("Running Echo version=2026.10.19", "commit=" + COMMIT,
                "dirty=false", "builtAt=2026-10-19T08:00:00Z");
    }

    @Test
    void preservesDirtyBuildsInsteadOfPresentingThemAsAnExactCommit() {
        BuildInfoService service = new BuildInfoService(Optional.of(properties(COMMIT, "true")));

        assertThat(service.getMetadata().commit()).isEqualTo(COMMIT);
        assertThat(service.getMetadata().dirty()).isTrue();
    }

    @Test
    void supportsDevelopmentWithoutGeneratedMetadata(CapturedOutput output) {
        BuildInfoService service = new BuildInfoService(Optional.empty());

        assertThat(service.getMetadata()).isEqualTo(new BuildInfoService.Metadata(null, null, null, null));
        service.logStartupVersion();
        assertThat(output).contains("version=dev", "commit=unknown", "dirty=unknown", "builtAt=unknown");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "abc123", "not-a-commit\nsecond-line"})
    void unknownOrInvalidCommitIsNotReportedAsClean(String commit) {
        BuildInfoService service = new BuildInfoService(Optional.of(properties(commit, "false")));

        assertThat(service.getMetadata().commit()).isNull();
        assertThat(service.getMetadata().shortCommit()).isNull();
        assertThat(service.getMetadata().dirty()).isNull();
    }

    @Test
    void unknownDirtyStateRemainsUnknown() {
        BuildInfoService service = new BuildInfoService(Optional.of(properties(COMMIT, "unknown")));

        assertThat(service.getMetadata().commit()).isEqualTo(COMMIT);
        assertThat(service.getMetadata().dirty()).isNull();
    }

    @Test
    void supportsSha256Repositories() {
        String commit = "a".repeat(64);
        BuildInfoService service = new BuildInfoService(Optional.of(properties(commit, "false")));

        assertThat(service.getMetadata().commit()).isEqualTo(commit);
        assertThat(service.getMetadata().shortCommit()).isEqualTo("a".repeat(12));
    }

    private static BuildProperties properties(String commit, String dirty) {
        Properties values = new Properties();
        values.setProperty("version", "2026.10.19");
        values.setProperty("git.commit", commit);
        values.setProperty("git.dirty", dirty);
        values.setProperty("time", BUILD_TIME.toString());
        return new BuildProperties(values);
    }
}
