package com.echo.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CommitAwareCaffeineCacheManagerTest {

    private final CommitAwareCaffeineCacheManager manager = new CommitAwareCaffeineCacheManager("rules");

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cacheStillExposesCaffeineNativeCache() {
        assertThat(manager.getCache("rules")).isInstanceOf(CaffeineCache.class);
    }

    @Test
    void clearOutsideTransaction_isImmediate() {
        Cache cache = manager.getCache("rules");
        cache.put("k", "v");

        cache.clear();

        assertThat(cache.get("k")).isNull();
    }

    @Test
    void clearInsideTransaction_clearsAgainAfterCommit() {
        Cache cache = manager.getCache("rules");
        cache.put("k", "old");
        TransactionSynchronizationManager.initSynchronization();

        cache.clear();
        assertThat(cache.get("k")).isNull();
        // Before commit, another request reads old data and puts it back in the cache.
        cache.put("k", "old");
        commit();

        assertThat(cache.get("k")).isNull();
    }

    @Test
    void evictInsideTransaction_evictsAgainAfterCommit() {
        Cache cache = manager.getCache("rules");
        TransactionSynchronizationManager.initSynchronization();

        cache.evict("k");
        cache.put("k", "old");
        cache.put("other", "kept");
        commit();

        assertThat(cache.get("k")).isNull();
        assertThat(cache.get("other")).isNotNull();
    }

    @Test
    void rollback_doesNotClearAgain() {
        Cache cache = manager.getCache("rules");
        TransactionSynchronizationManager.initSynchronization();

        cache.clear();
        cache.put("k", "unchanged");
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations,
                TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(cache.get("k")).isNotNull();
    }

    @Test
    void repeatedClearsInOneTransaction_registerOneSynchronization() {
        Cache cache = manager.getCache("rules");
        TransactionSynchronizationManager.initSynchronization();

        for (int i = 0; i < 1_000; i++) {
            cache.clear();
            cache.evict("k");
        }

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void changeListener_outsideTransaction_runsOnEveryClearOrEvict() {
        AtomicInteger changes = new AtomicInteger();
        manager.onChangeCommitted("rules", changes::incrementAndGet);
        Cache cache = manager.getCache("rules");

        cache.clear();
        cache.evict("k");
        cache.evictIfPresent("k");
        cache.invalidate();

        assertThat(changes).hasValue(4);
    }

    @Test
    void changeListener_insideTransaction_runsOnceAfterCommitAndAfterTheSecondClear() {
        List<Object> seenAfterCommit = new ArrayList<>();
        Cache cache = manager.getCache("rules");
        manager.onChangeCommitted("rules", () -> seenAfterCommit.add(String.valueOf(cache.get("k"))));
        TransactionSynchronizationManager.initSynchronization();

        cache.clear();
        cache.evict("k");
        cache.put("k", "old");
        assertThat(seenAfterCommit).as("not before commit").isEmpty();
        commit();

        // Run only once, after the post-commit invalidation: the stale value cached before commit is no longer visible.
        assertThat(seenAfterCommit).containsExactly("null");
    }

    @Test
    void changeListener_doesNotRunOnRollbackOrForOtherCaches() {
        AtomicInteger changes = new AtomicInteger();
        CommitAwareCaffeineCacheManager twoCaches = new CommitAwareCaffeineCacheManager("rules", "other");
        twoCaches.onChangeCommitted("rules", changes::incrementAndGet);

        twoCaches.getCache("other").clear();
        TransactionSynchronizationManager.initSynchronization();
        twoCaches.getCache("rules").clear();
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations,
                TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(changes).hasValue(0);
    }

    private static void commit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
    }
}
