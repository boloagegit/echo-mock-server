package com.echo.diagnostics;

import com.echo.config.DiagnosticProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TransactionDiagnosticsTest {
    @TempDir Path temporary;

    @Test
    void actualFileOpenFailureIsVisibleWithoutExportingErrorDetails() throws Exception {
        Path bad = temporary.resolve("not-a-directory");
        Files.writeString(bad, "existing");
        var sink = new DiagnosticFileSink(bad, Clock.systemDefaultZone(), 10000, 20000, 7);
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), sink)) {
            diagnostics.start();
            var trace = diagnostics.begin("JMS");
            trace.failure("FAILED", new RuntimeException("secret"));
            trace.end("FAILED");
            await().atMost(2, TimeUnit.SECONDS).until(() -> diagnostics.snapshot().get("diagnosticSinkFailures").equals(1L));
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticFileAvailable", false)
                    .containsEntry("diagnosticRunning", true);
            assertThat(diagnostics.snapshot().toString()).doesNotContain(bad.toString(), "secret");
        }
    }

    @Test
    void disabledGroupsDoNotEnqueueOrStartWriter() {
        var properties = new DiagnosticProperties();
        properties.setEnabled(false);
        try (var diagnostics = new TransactionDiagnostics(properties, line -> { throw new AssertionError(); })) {
            diagnostics.start();
            assertThat(diagnostics.begin("JMS").enabled()).isFalse();
            assertThat(diagnostics.begin("JMS").id()).isNull();
            diagnostics.begin("JMS").event("TEST");
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticQueueUsed", 0)
                    .containsEntry("diagnosticRunning", false).containsEntry("diagnosticAccepted", 0L);
        }
    }

    @Test
    void healthyTransactionsKeepUniqueIdsButWriteZeroBytesAndDoNotCreateFiles() {
        var ids = new HashSet<String>();
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), temporary.toString())) {
            diagnostics.start();
            for (int i = 0; i < 1000; i++) {
                var trace = diagnostics.begin("JMS");
                assertThat(ids.add(trace.id())).isTrue();
                trace.event("RECEIVED", "messageId", "ID:" + i);
                trace.event("OUTBOUND_SEND_RETURNED", "messageId", "ID:out", "replyTo", "temp");
                trace.event("REPLY_WAIT_END", "outcome", "TEXT_REPLY", "waitMs", 1);
                trace.event("REPLY_SEND_RETURNED");
                trace.end("PROCESSING_ENDED");
                trace.end("DUPLICATE");
            }
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticAccepted", 0L)
                    .containsEntry("diagnosticDropped", 0L).containsEntry("diagnosticTodayBytes", 0L)
                    .containsEntry("diagnosticFileWriteAttempted", false);
            assertThat(temporary.resolve("diagnostics")).doesNotExist();
        }
    }

    @Test
    void anomaliesHaveExactlyOneBoundedUtf8SingleLineSummary() {
        var lines = new CopyOnWriteArrayList<String>();
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), lines::add)) {
            diagnostics.start();
            var trace = diagnostics.begin("JMS");
            trace.event("RECEIVED", "messageId", "ID:in", "queue", "a\r\nb" + "長".repeat(10000));
            trace.event("FORWARD_BEGIN", "queue", "target", "timeoutMs", 30000);
            trace.event("OUTBOUND_SEND_RETURNED", "messageId", "ID:out", "replyTo", "temporary");
            trace.event("REPLY_WAIT_END", "outcome", "NO_REPLY", "waitMs", 30000);
            trace.event("REPLY_SEND_RETURNED");
            trace.end("PROCESSING_ENDED");
            trace.end("DUPLICATE");
            await().atMost(2, TimeUnit.SECONDS).until(() -> lines.size() == 1);
            assertThat(lines.get(0)).contains("inId=ID:in", "outId=ID:out", "outReplyTo=temporary",
                    "failure=NO_REPLY", "stage=RECEIVE_REPLY", "waitMs=30000", "callerReply=SEND_RETURNED")
                    .doesNotContain("\n", "\r", "DUPLICATE");
            assertThat(lines.get(0).getBytes(StandardCharsets.UTF_8).length + 1)
                    .isLessThanOrEqualTo(TransactionDiagnostics.MAX_LINE_BYTES);
        }
    }

    @Test
    void httpConfigurationKeepsIdVersionTimeoutAndObservedStageDistinct() {
        var lines = new CopyOnWriteArrayList<String>();
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), lines::add)) {
            diagnostics.start();
            var trace = diagnostics.begin("HTTP");
            trace.stage("HTTP_EXCHANGE");
            trace.event("FORWARD_BEGIN", "connectionId", 9L, "connectionVersion", 3L,
                    "source", "PROFILE", "readTimeoutMs", 5000L);
            trace.failure("FORWARD_FAILED", new IllegalStateException("secret URL"));
            trace.end("HTTP_ASYNC_COMPLETED_NOT_DELIVERY_CONFIRMATION");
            await().atMost(2, TimeUnit.SECONDS).until(() -> lines.size() == 1);
            assertThat(lines.get(0)).contains("connectionId=9", "connectionVersion=3", "timeoutMs=5000",
                    "stage=HTTP_EXCHANGE", "failure=FORWARD_FAILED").doesNotContain("connectionRevision=9", "secret URL");
        }
    }

    @Test
    void longProviderIdentifiersDoNotHideWaitCallerOrCleanupResults() {
        var lines = new CopyOnWriteArrayList<String>();
        try (var diagnostics = new TransactionDiagnostics(new DiagnosticProperties(), lines::add)) {
            diagnostics.start();
            var trace = diagnostics.begin("JMS");
            String longId = "長".repeat(128);
            trace.event("RECEIVED", "messageId", longId, "correlationId", longId, "replyTo", longId);
            trace.event("FORWARD_BEGIN", "endpoint", longId, "queue", longId, "source", longId);
            trace.event("OUTBOUND_SEND_RETURNED", "messageId", longId, "replyTo", longId);
            trace.event("REPLY_WAIT_END", "outcome", "NO_REPLY", "waitMs", 30000);
            trace.failure("REPLY_SEND_FAILED", new IllegalStateException("secret"));
            trace.failure("TEMP_QUEUE_CLEANUP_FAILED", new IllegalStateException("secret"));
            trace.end("PROCESSING_ENDED");
            await().atMost(2, TimeUnit.SECONDS).until(() -> lines.size() == 1);
            assertThat(lines.get(0)).contains("waitResult=NO_REPLY", "waitMs=30000", "sendReturned=true",
                    "callerReply=SEND_FAILED", "cleanupFailure=true", "truncated=true").doesNotContain("secret");
            assertThat(lines.get(0).getBytes(StandardCharsets.UTF_8).length + 1).isLessThanOrEqualTo(2048);
        }
    }

    @Test
    void fullQueueAndStalledSinkNeverBlockProducerAndShutdownIsBounded() throws Exception {
        var properties = new DiagnosticProperties();
        properties.setQueueCapacity(32);
        properties.setDetailedEnabled(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var diagnostics = new TransactionDiagnostics(properties, line -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) {
                    // Deliberately ignore interruption to simulate a stalled filesystem.
                }
            }
        });
        diagnostics.start();
        try {
            var trace = diagnostics.begin("JMS");
            trace.event("FIRST");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 1000; i++) trace.event("TEST", "sequence", i);
            }).get(2, TimeUnit.SECONDS);
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticQueueUsed", 32)
                    .containsEntry("diagnosticDropped", 968L);
            CompletableFuture.runAsync(diagnostics::close).get(2, TimeUnit.SECONDS);
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticQueueUsed", 0)
                    .containsEntry("diagnosticDropped", 1000L).containsEntry("diagnosticRunning", false);
        } finally { release.countDown(); diagnostics.close(); }
    }

    @Test
    void sinkFailureIsCountedAndDoesNotKillWriterOrThrowIntoRequest() {
        var p = new DiagnosticProperties();
        p.setRecordsPerSecond(100);
        try (var diagnostics = new TransactionDiagnostics(p, line -> { throw new IllegalStateException("sink failed"); })) {
            diagnostics.start();
            for (int i = 0; i < 50; i++) {
                var trace = diagnostics.begin("JMS");
                trace.failure("FAILED", new RuntimeException("secret-body"));
                trace.end("FAILED");
            }
            await().atMost(2, TimeUnit.SECONDS).until(() -> diagnostics.snapshot().get("diagnosticSinkFailures").equals(50L));
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticRunning", true).containsEntry("diagnosticQueueUsed", 0);
        }
    }

    @Test
    void switchesAndCapacityBoundsAreIndependent() {
        var p = new DiagnosticProperties();
        p.setJmsEnabled(false); p.setSseEnabled(false);
        p.setQueueCapacity(Integer.MAX_VALUE);
        assertThat(p.boundedQueueCapacity()).isEqualTo(4096);
        p.setQueueCapacity(-1);
        assertThat(p.boundedQueueCapacity()).isEqualTo(32);
        try (var diagnostics = new TransactionDiagnostics(p, line -> { })) {
            diagnostics.start();
            assertThat(diagnostics.begin("JMS").enabled()).isFalse();
            assertThat(diagnostics.begin("SSE").enabled()).isFalse();
            assertThat(diagnostics.begin("UNKNOWN").enabled()).isFalse();
            assertThat(diagnostics.begin("HTTP").enabled()).isTrue();
        }
    }

    @Test
    void detailedTracingAutomaticallyExpiresAndDoesNotRestartPerRequest() {
        var p = new DiagnosticProperties();
        p.setDetailedEnabled(true); p.setDetailedSeconds(1);
        var lines = new CopyOnWriteArrayList<String>();
        try (var diagnostics = new TransactionDiagnostics(p, lines::add)) {
            diagnostics.start();
            diagnostics.begin("JMS").event("RECEIVED");
            await().atMost(2, TimeUnit.SECONDS).until(() -> !((Boolean) diagnostics.snapshot().get("diagnosticDetailed")));
            long before = (Long) diagnostics.snapshot().get("diagnosticAccepted");
            var trace = diagnostics.begin("JMS"); trace.event("RECEIVED"); trace.end("PROCESSING_ENDED");
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticAccepted", before);
        }
    }

    @Test
    void repeatedAnomaliesAreRateLimitedAndOmissionsAreVisible() {
        var p = new DiagnosticProperties(); p.setRecordsPerSecond(1);
        var lines = new CopyOnWriteArrayList<String>();
        try (var diagnostics = new TransactionDiagnostics(p, lines::add)) {
            diagnostics.start();
            for (int i = 0; i < 100; i++) {
                var trace = diagnostics.begin("JMS"); trace.failure("FAILED", null); trace.end("FAILED");
            }
            await().atMost(2, TimeUnit.SECONDS).until(() -> ((Long) diagnostics.snapshot().get("diagnosticRateLimited")) > 0);
            assertThat(lines.size()).isLessThanOrEqualTo(2);
        }
    }
}
