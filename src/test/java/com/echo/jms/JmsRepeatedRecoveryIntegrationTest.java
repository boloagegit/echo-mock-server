package com.echo.jms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JmsRepeatedRecoveryIntegrationTest {
    @TempDir Path data;

    @Test
    @Timeout(90)
    void repeatedPersistentTcpRestartsKeepRepliesIsolatedAndResourcesBounded() throws Exception {
        var report = JmsRecoveryProbe.exercise(data, 2, Duration.ZERO, checkpoint -> { });
        assertThat(report.passed()).isTrue();
        assertThat(report.checkedReplies()).isEqualTo(27);
        assertThat(report.expectedFailureReplies()).isGreaterThanOrEqualTo(6);
        assertThat(report.finalCheckpoint().liveFactories()).isEqualTo(3);
    }
}
