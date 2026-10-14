package com.echo.service;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 規則快照：規則變更後在背景重建，重建期間請求繼續使用舊快照，不必等待。
 *
 * <p>每次變更遞增一個序號；快照記得建立前讀到的序號，較舊的建立結果不會蓋掉較新的。
 * 建立期間又有變更時，結束後再建一次（多次變更合併成一次）。只有第一次（還沒有任何快照）
 * 由呼叫端同步建立。</p>
 *
 * <p>做出變更的一方要等新快照生效：網頁請求在回應前等一次（{@link #awaitPendingChanges()}，
 * 一次批次匯入只等一次），其他執行緒（多台同步、排程清理）在變更當下等待。
 * 如此透過 API 存檔後立刻送出的模擬請求一定會用到新規則。</p>
 */
@Slf4j
public final class RuleSnapshotCache<K, V> {

    /** 這個執行緒在目前網頁請求中變更過的快取 → 需要等到的序號。 */
    private static final ThreadLocal<Map<RuleSnapshotCache<?, ?>, Long>> PENDING = new ThreadLocal<>();
    private static final long FAILURE_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int FIRST_BUILD_ATTEMPTS = 3;

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

    /** 目前的快照；過期或有變更時在背景重建並先回傳舊的。第一次由呼叫端建立。 */
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
     * 規則已變更（提交後呼叫）。網頁請求中記下要等的序號，回應前由 {@link #awaitPendingChanges()} 等待；
     * 其他執行緒直接等到新快照生效。
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

    /** 等到這個網頁請求做出的變更都已生效（每個請求結束前呼叫；沒有變更時立即返回）。 */
    public static void awaitPendingChanges() {
        Map<RuleSnapshotCache<?, ?>, Long> pending = PENDING.get();
        if (pending == null) {
            return;
        }
        PENDING.remove();
        // 一個請求同時改到 HTTP 與 JMS 規則時，兩者共用同一個等待期限
        long deadline = 0;
        for (Map.Entry<RuleSnapshotCache<?, ?>, Long> entry : pending.entrySet()) {
            RuleSnapshotCache<?, ?> cache = entry.getKey();
            if (deadline == 0) {
                deadline = cache.deadlineFromNow();
            }
            cache.awaitUpToDate(entry.getValue(), deadline);
        }
    }

    /** 丟掉這個執行緒記下的待等變更（請求開始時呼叫，避免沿用上一個請求的狀態）。 */
    public static void clearPendingChanges() {
        PENDING.remove();
    }

    /** 等到所有已建立的快照都包含這個序號的變更；逾時則記錄並放行（之後的請求仍會在背景更新）。 */
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

    private boolean upToDate(long generation) {
        for (Slot<V> slot : slots.values()) {
            Built<V> current = slot.current;
            if (current != null && current.generation < generation) {
                return false;
            }
        }
        return true;
    }

    /**
     * 還沒有快照時由呼叫端建立（同一個 key 的並行請求在 slot 上等這一次）。建立期間有變更提交時重建，
     * 直到建立的結果包含開始建立前的所有變更：變更方看不到尚未完成的第一份快照，不會等它，
     * 所以第一份快照不能比變更舊。持續變更時最多重建 {@value #FIRST_BUILD_ATTEMPTS} 次，之後交給背景。
     */
    private V buildFirst(K key, Slot<V> slot) {
        synchronized (slot) {
            Built<V> current = slot.current;
            if (current != null) {
                return current.value;
            }
            long generation;
            V value;
            int attempts = 0;
            do {
                generation = changes.get();
                value = builder.apply(key);
            } while (changes.get() > generation && ++attempts < FIRST_BUILD_ATTEMPTS);
            install(slot, value, generation);
            if (changes.get() > generation) {
                requestRebuild(key, slot, nanoTime.getAsLong());
            }
            return value;
        }
    }

    private void requestRebuild(K key, Slot<V> slot, long now) {
        if (now - slot.lastFailureAt < FAILURE_BACKOFF_NANOS && slot.lastFailureAt != 0) {
            return;
        }
        if (slot.queued.compareAndSet(false, true)) {
            try {
                executor.execute(() -> rebuild(key, slot));
            } catch (RuntimeException e) {
                slot.queued.set(false);
                log.warn("{} rule snapshot rebuild could not be scheduled: {}", name, e.toString());
            }
        }
    }

    /**
     * 建立期間 {@code queued} 保持 true，期間的請求與變更不再排重建；建完後若序號已前進，再建一次
     * （變更先遞增序號才嘗試排重建，所以清掉 {@code queued} 後一定看得到這段期間的變更）。
     */
    private void rebuild(K key, Slot<V> slot) {
        long generation = changes.get();
        V value;
        try {
            value = builder.apply(key);
        } catch (RuntimeException e) {
            slot.lastFailureAt = nanoTime.getAsLong();
            slot.queued.set(false);
            log.warn("{} rule snapshot rebuild failed; still serving the previous rules: {}", name, e.toString());
            return;
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
            installed.notifyAll();
        }
    }

    /** 測試用：目前快照包含到的變更序號。 */
    long generationOf(K key) {
        Slot<V> slot = slots.get(key);
        Built<V> current = slot == null ? null : slot.current;
        return current == null ? -1 : current.generation;
    }

    private static final class Slot<V> {
        private volatile Built<V> current;
        private final AtomicBoolean queued = new AtomicBoolean();
        private volatile long lastFailureAt;
    }

    private record Built<V>(V value, long generation, long builtAt) {
    }
}
