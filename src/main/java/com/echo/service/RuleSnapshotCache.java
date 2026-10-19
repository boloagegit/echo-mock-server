package com.echo.service;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Rule snapshots rebuild in the background after changes; requests continue using the previous snapshot without waiting.
 *
 * <p>Increment a revision for every change. Each snapshot remembers the revision read before building, and an older build cannot replace a newer one.
 * If rules change during a build, rebuild after it finishes, coalescing changes into one build. Only the first snapshot
 * is built synchronously by its caller.</p>
 *
 * <p>The caller making a change waits for the new snapshot: a web request waits once before responding ({@link #awaitPendingChanges()},
 * once per batch import); other threads, such as cross-instance synchronization and scheduled cleanup, wait at the change point.
 * A mock request sent immediately after saving through the API therefore uses the new rules.</p>
 */
@Slf4j
public final class RuleSnapshotCache<K, V> {

    /** Caches changed by this thread during the current web request, mapped to revisions to await. */
    private static final ThreadLocal<Map<RuleSnapshotCache<?, ?>, Long>> PENDING = new ThreadLocal<>();
    private static final long FAILURE_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final String name;
    private final Function<K, V> builder;
    private final Executor executor;
    private final long maxAgeNanos;
    private final long waitTimeoutMillis;
    private final LongSupplier nanoTime;

    private final AtomicLong changes = new AtomicLong();
    private final Map<K, Slot<V>> slots = new ConcurrentHashMap<>();
    private final Object installed = new Object();

    public RuleSnapshotCache(String name, Function<K, V> builder, Executor executor,
                             long maxAgeNanos, long waitTimeoutMillis, LongSupplier nanoTime) {
        this.name = name;
        this.builder = builder;
        this.executor = executor;
        this.maxAgeNanos = maxAgeNanos;
        this.waitTimeoutMillis = waitTimeoutMillis;
        this.nanoTime = nanoTime;
    }

    /** Get the current snapshot; return the old one while rebuilding expired or changed data in the background. Build the first snapshot on the caller thread. */
    public V get(K key) {
        Slot<V> slot = slots.computeIfAbsent(key, k -> new Slot<>());
        Built<V> current = slot.current;
        if (current == null) {
            return buildFirst(key, slot);
        }
        long now = nanoTime.getAsLong();
        if (current.generation < changes.get() || now - current.builtAt >= maxAgeNanos) {
            requestRebuild(key, slot, now);
        }
        return current.value;
    }

    /**
     * Called after commit when rules change. During a web request, record the revision to await; {@link #awaitPendingChanges()} waits before responding,
     * Other threads wait directly until the new snapshot takes effect.
     */
    public void changed(boolean inWebRequest) {
        long generation = changes.incrementAndGet();
        long now = nanoTime.getAsLong();
        slots.forEach((key, slot) -> {
            if (slot.current != null) {
                requestRebuild(key, slot, now);
            }
        });
        if (inWebRequest) {
            Map<RuleSnapshotCache<?, ?>, Long> pending = PENDING.get();
            if (pending == null) {
                pending = new HashMap<>();
                PENDING.set(pending);
            }
            pending.merge(this, generation, Math::max);
        } else {
            awaitUpToDate(generation);
        }
    }

    /** Wait for every change made by this web request before the request ends; return immediately if none were made. */
    public static void awaitPendingChanges() {
        Map<RuleSnapshotCache<?, ?>, Long> pending = PENDING.get();
        if (pending == null) {
            return;
        }
        PENDING.remove();
        // A request changing both HTTP and JMS rules shares one wait deadline across both.
        long deadline = 0;
        for (Map.Entry<RuleSnapshotCache<?, ?>, Long> entry : pending.entrySet()) {
            RuleSnapshotCache<?, ?> cache = entry.getKey();
            if (deadline == 0) {
                deadline = cache.deadlineFromNow();
            }
            cache.awaitUpToDate(entry.getValue(), deadline);
        }
    }

    /** Discard this thread's pending changes at request start so state cannot leak from the previous request. */
    public static void clearPendingChanges() {
        PENDING.remove();
    }

    /** Wait until snapshots include this revision; on timeout, log and proceed while later requests still trigger background refresh. */
    boolean awaitUpToDate(long generation) {
        return awaitUpToDate(generation, deadlineFromNow());
    }

    private long deadlineFromNow() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitTimeoutMillis);
    }

    private boolean awaitUpToDate(long generation, long deadline) {
        synchronized (installed) {
            while (!upToDate(generation)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    log.warn("{} rule snapshot did not include change {} within {} ms; continuing with the "
                            + "previous rules until the rebuild finishes", name, generation, waitTimeoutMillis);
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(installed, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /** Do not update a slot while its first snapshot is being built; that result may predate this change. */
    private boolean upToDate(long generation) {
        for (Slot<V> slot : slots.values()) {
            Built<V> current = slot.current;
            if (current == null ? slot.firstBuilding : current.generation < generation) {
                return false;
            }
        }
        return true;
    }

    /**
     * The caller is building the first snapshot, and concurrent requests for its key await the same slot. Changes during that build must wait:
     * if its result predates the change, schedule a background rebuild after it finishes, then wait for that rebuild (see {@link #upToDate}).
     */
    private V buildFirst(K key, Slot<V> slot) {
        synchronized (slot) {
            Built<V> current = slot.current;
            if (current != null) {
                return current.value;
            }
            slot.firstBuilding = true;
            try {
                long generation = changes.get();
                V value = builder.apply(key);
                install(slot, value, generation);
                if (changes.get() > generation) {
                    requestRebuild(key, slot, nanoTime.getAsLong());
                }
                return value;
            } finally {
                if (slot.firstBuilding) {
                    // If creation failed, stop making the change caller wait for this snapshot.
                    synchronized (installed) {
                        slot.firstBuilding = false;
                        installed.notifyAll();
                    }
                }
            }
        }
    }

    /**
     * Schedule one background rebuild unless one is queued or running. After a failure, defer it until the retry interval ends without losing the request,
     * so pending changes can complete after that interval rather than waiting for a timeout.
     */
    private void requestRebuild(K key, Slot<V> slot, long now) {
        if (!slot.queued.compareAndSet(false, true)) {
            return;
        }
        // A reader can hold an expired snapshot while another rebuild finishes. Recheck
        // after claiming the slot so that stale decision cannot rebuild an already fresh value.
        Built<V> latest = slot.current;
        if (latest != null && latest.generation >= changes.get() && now - latest.builtAt < maxAgeNanos) {
            slot.queued.set(false);
            // A change may have observed our claimed slot and left scheduling to us. Hand
            // it off after releasing the slot, just as the completed-build path does below.
            if (latest.generation < changes.get()) {
                requestRebuild(key, slot, nanoTime.getAsLong());
            }
            return;
        }
        long sinceFailure = now - slot.lastFailureAt;
        Executor target = slot.lastFailureAt != 0 && sinceFailure < FAILURE_BACKOFF_NANOS
                ? CompletableFuture.delayedExecutor(FAILURE_BACKOFF_NANOS - sinceFailure, TimeUnit.NANOSECONDS,
                        executor)
                : executor;
        try {
            target.execute(() -> rebuild(key, slot));
        } catch (RuntimeException e) {
            slot.queued.set(false);
            log.warn("{} rule snapshot rebuild could not be scheduled: {}", name, e.toString());
        }
    }

    /**
     * Keep {@code queued} true during a build so requests and changes cannot schedule duplicates; if the revision advanced, rebuild after completion.
     * Changes increment the revision before requesting a rebuild, so clearing {@code queued} cannot hide changes made during the build.
     * Retain the old snapshot on failure; retry after the backoff until the snapshot catches up with the changes.
     */
    private void rebuild(K key, Slot<V> slot) {
        long generation = changes.get();
        V value;
        try {
            value = builder.apply(key);
        } catch (RuntimeException e) {
            slot.lastFailureAt = nanoTime.getAsLong();
            slot.queued.set(false);
            if (!slot.failing) {
                slot.failing = true;
                log.warn("{} rule snapshot rebuild failed; still serving the previous rules and retrying every "
                        + "second: {}", name, e.toString());
            } else {
                log.debug("{} rule snapshot rebuild failed again: {}", name, e.toString());
            }
            Built<V> current = slot.current;
            if (current != null && current.generation < changes.get()) {
                requestRebuild(key, slot, slot.lastFailureAt);
            }
            return;
        }
        if (slot.failing) {
            slot.failing = false;
            log.info("{} rule snapshot rebuilt again after failures", name);
        }
        slot.lastFailureAt = 0;
        install(slot, value, generation);
        slot.queued.set(false);
        if (changes.get() > generation) {
            requestRebuild(key, slot, nanoTime.getAsLong());
        }
    }

    private void install(Slot<V> slot, V value, long generation) {
        Objects.requireNonNull(value, "rule snapshot");
        synchronized (installed) {
            Built<V> current = slot.current;
            if (current == null || generation >= current.generation) {
                slot.current = new Built<>(value, generation, nanoTime.getAsLong());
            }
            slot.firstBuilding = false;
            installed.notifyAll();
        }
    }

    /** For testing: the change sequence number contained in the current snapshot. */
    long generationOf(K key) {
        Slot<V> slot = slots.get(key);
        Built<V> current = slot == null ? null : slot.current;
        return current == null ? -1 : current.generation;
    }

    private static final class Slot<V> {
        private volatile Built<V> current;
        private final AtomicBoolean queued = new AtomicBoolean();
        private volatile long lastFailureAt;
        /** Builder-thread only: log one warning for consecutive failures. */
        private boolean failing;
        /** The first snapshot is being created (no {@code current} yet). */
        private volatile boolean firstBuilding;
    }

    private record Built<V>(V value, long generation, long builtAt) {
    }
}
