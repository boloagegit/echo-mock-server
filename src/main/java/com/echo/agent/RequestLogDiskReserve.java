package com.echo.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Writer-side disk reserve. Request threads only read its cached state; no disk I/O. */
@Component
@ConditionalOnProperty(name = "echo.request-log.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "echo.request-log.store", havingValue = "database", matchIfMissing = true)
@Slf4j
public final class RequestLogDiskReserve {
    private static final long CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final long minimumBytes;
    private final LongSupplier usableSpace;
    private final LongSupplier nanoTime;
    private final AtomicLong probeFailures = new AtomicLong();
    private volatile Snapshot snapshot = new Snapshot(-1, false);
    private long nextCheck;
    private boolean checked;

    @Autowired
    public RequestLogDiskReserve(
            @Value("${echo.request-log.durable.spool-path:./data/request-log-spool.sqlite}") String path,
            @Value("${echo.request-log.durable.min-free-bytes:536870912}") long minimumBytes) {
        this(minimumBytes, () -> {
            try {
                return Files.getFileStore(Path.of(path).toAbsolutePath().normalize()).getUsableSpace();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, System::nanoTime);
    }

    RequestLogDiskReserve(long minimumBytes, LongSupplier usableSpace, LongSupplier nanoTime) {
        if (minimumBytes < 0) throw new IllegalArgumentException("Request-log minimum free bytes must not be negative");
        this.minimumBytes = minimumBytes;
        this.usableSpace = usableSpace;
        this.nanoTime = nanoTime;
    }

    /** Called by the single spool writer, including while no appends are arriving. */
    synchronized boolean refresh() {
        if (minimumBytes == 0) return true;
        long now = nanoTime.getAsLong();
        if (checked && now - nextCheck < 0) return !snapshot.blocked();
        checked = true;
        nextCheck = now + CHECK_INTERVAL_NANOS;
        long usable = -1;
        try {
            usable = usableSpace.getAsLong();
            if (usable < 0) throw new IllegalStateException("Disk space unavailable");
        } catch (RuntimeException error) {
            probeFailures.incrementAndGet();
        }
        boolean blocked = usable < minimumBytes;
        if (blocked != snapshot.blocked()) {
            if (blocked) {
                log.warn("Request-log disk reserve reached or unavailable: usableBytes={}, minimumFreeBytes={}; "
                        + "new spool writes are paused", usable, minimumBytes);
            } else {
                log.info("Request-log disk reserve recovered; spool writes may resume");
            }
        }
        snapshot = new Snapshot(usable, blocked);
        return !blocked;
    }

    boolean blocked() { return snapshot.blocked(); }

    Map<String, Object> metrics() {
        Snapshot current = snapshot;
        return current.usableBytes() < 0
                ? Map.of("diskReserveBytes", minimumBytes, "diskSpaceBlocked", current.blocked(),
                         "diskSpaceProbeFailures", probeFailures.get())
                : Map.of("diskReserveBytes", minimumBytes, "diskSpaceBlocked", current.blocked(),
                         "diskSpaceProbeFailures", probeFailures.get(), "diskUsableBytes", current.usableBytes());
    }

    private record Snapshot(long usableBytes, boolean blocked) { }
}
