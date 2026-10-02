package com.echo.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class DiagnosticFileSinkTest {
    @TempDir Path directory;
    private static Clock fixed(String date) { return Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC); }

    @Test
    void actualUtf8BytesAndNewlineCannotExceedDailyBudgetEvenAfterRestart() throws Exception {
        Path file = directory.resolve("transactions-2026-10-01.log");
        try (var sink = new DiagnosticFileSink(directory, fixed("2026-10-01"), 10, 100, 7)) {
            sink.accept("長長"); // six UTF-8 bytes plus newline
            sink.accept("long"); // would exceed ten
            assertThat(Files.size(file)).isEqualTo(7);
            assertThat(sink.todayBytes()).isEqualTo(7);
            assertThat(sink.budgetDropped()).isEqualTo(1);
        }
        try (var sink = new DiagnosticFileSink(directory, fixed("2026-10-01"), 10, 100, 7)) {
            sink.accept("ok"); sink.accept("x");
            assertThat(Files.size(file)).isEqualTo(10);
            assertThat(sink.budgetDropped()).isEqualTo(1);
        }
    }

    @Test
    void nextDayGetsNewQuotaAndOnlyOldOwnedFilesArePruned() throws Exception {
        AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-10-01T12:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return instant.get(); }
        };
        Path unrelated = directory.resolve("echo.log"); Files.writeString(unrelated, "keep");
        try (var sink = new DiagnosticFileSink(directory, clock, 10, 12, 7)) {
            sink.accept("123456789"); sink.accept("ignored");
            instant.set(Instant.parse("2026-10-02T12:00:00Z"));
            sink.accept("abcdefghij"); // too large: no prior-day removal needed
            sink.accept("123456789"); // prune Oct 1 to make total room
            assertThat(directory.resolve("transactions-2026-10-01.log")).doesNotExist();
            assertThat(sink.todayBytes()).isEqualTo(10);
            assertThat(sink.retainedBytes()).isEqualTo(10);
            assertThat(Files.readString(unrelated)).isEqualTo("keep");
        }
    }

    @Test
    void retentionKeepsSevenCalendarDaysIncludingTodayAndIgnoresUnknownFiles() throws Exception {
        Files.writeString(directory.resolve("transactions-2026-09-24.log"), "expired");
        Files.writeString(directory.resolve("transactions-2026-09-25.log"), "keep");
        Files.writeString(directory.resolve("transactions-2026-99-99.log"), "unknown");
        try (var sink = new DiagnosticFileSink(directory, fixed("2026-10-01"), 100, 100, 7)) { sink.accept("today"); }
        assertThat(directory.resolve("transactions-2026-09-24.log")).doesNotExist();
        assertThat(directory.resolve("transactions-2026-09-25.log")).exists();
        assertThat(directory.resolve("transactions-2026-99-99.log")).exists();
    }

    @Test
    void expiredFilesArePrunedEvenWithoutNewAnomaliesAndNoNewLogIsCreated() throws Exception {
        Files.writeString(directory.resolve("transactions-2026-09-24.log"), "expired");
        Files.writeString(directory.resolve("transactions-2026-09-25.log"), "keep");
        try (var sink = new DiagnosticFileSink(directory, fixed("2026-10-01"), 100, 100, 7)) {
            sink.maintain();
            assertThat(sink.attempted()).isFalse();
            assertThat(sink.retainedBytes()).isEqualTo(4);
            assertThat(directory.resolve("transactions-2026-10-01.log")).doesNotExist();
        }
        assertThat(directory.resolve("transactions-2026-09-24.log")).doesNotExist();
    }

    @Test
    void currentDayIsNotDeletedToResetQuotaWhenTotalBudgetIsSmaller() throws Exception {
        try (var sink = new DiagnosticFileSink(directory, fixed("2026-10-01"), 100, 10, 7)) {
            sink.accept("123456789"); sink.accept("new");
            assertThat(sink.retainedBytes()).isEqualTo(10);
            assertThat(sink.todayBytes()).isEqualTo(10);
            assertThat(sink.budgetDropped()).isEqualTo(1);
        }
    }

    @Test
    void symlinkAndConcurrentWriterCannotBypassQuotaOrModifyOtherFiles() throws Exception {
        Path outside = directory.resolve("outside"); Files.writeString(outside, "keep");
        Path logs = directory.resolve("diagnostics"); Files.createDirectory(logs);
        Files.createSymbolicLink(logs.resolve("transactions-2026-10-01.log"), outside);
        try (var sink = new DiagnosticFileSink(logs, fixed("2026-10-01"), 100, 100, 7)) {
            assertThatThrownBy(() -> sink.accept("bad")).isInstanceOf(DiagnosticFileSink.DiagnosticWriteException.class);
        }
        assertThat(Files.readString(outside)).isEqualTo("keep");
        Path otherLogs = directory.resolve("other");
        try (var first = new DiagnosticFileSink(otherLogs, fixed("2026-10-01"), 100, 100, 7);
             var second = new DiagnosticFileSink(otherLogs, fixed("2026-10-01"), 100, 100, 7)) {
            first.accept("first");
            assertThatThrownBy(() -> second.accept("second")).isInstanceOf(DiagnosticFileSink.DiagnosticWriteException.class);
            assertThat(Files.readString(otherLogs.resolve("transactions-2026-10-01.log"))).isEqualTo("first\n");
        }
    }
}
