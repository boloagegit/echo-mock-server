package com.echo.diagnostics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/** Single-worker, lazy file sink. Quotas include UTF-8 and newline; restart preserves quotas.
 * Only strictly named regular diagnostic files may be pruned. No compression/temp copies. */
public final class DiagnosticFileSink implements Consumer<String>, AutoCloseable {
    private final Path directory;
    private final Clock clock;
    private final long dailyLimit;
    private final long retainedLimit;
    private final int retentionDays;
    private FileChannel lockChannel;
    private FileLock lock;
    private LocalDate day;
    private final AtomicLong todayBytes = new AtomicLong();
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicLong budgetDropped = new AtomicLong();
    private volatile boolean available = true;
    private volatile boolean attempted;
    private List<Path> files = List.of();

    public DiagnosticFileSink(Path directory, Clock clock, long dailyLimit,
                              long retainedLimit, int retentionDays) {
        this.directory = directory; this.clock = clock; this.dailyLimit = dailyLimit;
        this.retainedLimit = retainedLimit; this.retentionDays = retentionDays;
    }

    @Override
    public void accept(String line) {
        attempted = true;
        try {
            LocalDate today = LocalDate.now(clock);
            if (!today.equals(day)) initialize(today);
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > dailyLimit - todayBytes.get()) { budgetDropped.incrementAndGet(); return; }
            prune(today, bytes.length);
            if (bytes.length > retainedLimit - retainedBytes.get()) { budgetDropped.incrementAndGet(); return; }
            Path file = file(today);
            try (FileChannel output = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    int written = output.write(buffer);
                    todayBytes.addAndGet(written); retainedBytes.addAndGet(written);
                }
            }
            if (!files.contains(file)) files = java.util.stream.Stream.concat(files.stream(),
                    java.util.stream.Stream.of(file)).sorted().toList();
            available = true;
        } catch (IOException | RuntimeException error) {
            available = false;
            throw new DiagnosticWriteException(error);
        }
    }

    /** Cleanup of an existing directory only; never create output for healthy traffic. */
    public void maintain() {
        try {
            LocalDate today = LocalDate.now(clock);
            if (!today.equals(day) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) initialize(today);
        } catch (IOException | RuntimeException error) {
            available = false;
            throw new DiagnosticWriteException(error);
        }
    }

    private void initialize(LocalDate today) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Diagnostic directory must not be a symlink");
        if (lock == null) {
            lockChannel = FileChannel.open(directory.resolve(".writer.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { lock = lockChannel.tryLock(); }
            catch (RuntimeException | IOException error) { lockChannel.close(); lockChannel = null; throw error; }
            if (lock == null) { lockChannel.close(); lockChannel = null; throw new IOException("Diagnostic directory in use"); }
        }
        List<Path> owned = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "transactions-????-??-??.log")) {
            for (Path path : entries) if (date(path) != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) owned.add(path);
        }
        owned.sort(Comparator.naturalOrder()); files = owned;
        retainedBytes.set(0);
        for (Path path : files) retainedBytes.addAndGet(Files.size(path));
        Path current = file(today);
        todayBytes.set(Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) ? Files.size(current) : 0);
        prune(today, 0); day = today;
    }

    private void prune(LocalDate today, long incomingBytes) throws IOException {
        List<Path> remaining = new ArrayList<>();
        for (Path path : files) {
            LocalDate date = date(path);
            boolean expired = date != null && date.isBefore(today.minusDays(retentionDays - 1L));
            boolean needsRoom = incomingBytes > retainedLimit - retainedBytes.get();
            // Current-day size is the durable daily quota. Never prune current/future/unknown files.
            if (date != null && date.isBefore(today) && (expired || needsRoom)) {
                long size = Files.size(path); Files.delete(path); retainedBytes.addAndGet(-size);
            } else remaining.add(path);
        }
        files = remaining;
    }
    private Path file(LocalDate date) { return directory.resolve("transactions-" + date + ".log"); }
    private static LocalDate date(Path path) {
        Path filename = path.getFileName();
        if (filename == null) return null;
        String name = filename.toString();
        if (!name.matches("transactions-\\d{4}-\\d{2}-\\d{2}\\.log")) return null;
        try { return LocalDate.parse(name.substring(13, 23)); }
        catch (DateTimeParseException ignored) { return null; }
    }
    public long todayBytes() { return todayBytes.get(); }
    public long retainedBytes() { return retainedBytes.get(); }
    public long budgetDropped() { return budgetDropped.get(); }
    public boolean available() { return available; }
    public boolean attempted() { return attempted; }
    @Override
    public void close() {
        try { if (lock != null) lock.release(); }
        catch (IOException ignored) { available = false; }
        finally {
            try { if (lockChannel != null) lockChannel.close(); }
            catch (IOException ignored) { available = false; }
            lock = null; lockChannel = null;
        }
    }
    public static final class DiagnosticWriteException extends RuntimeException {
        DiagnosticWriteException(Throwable cause) { super("Diagnostic output unavailable", cause); }
    }
}
