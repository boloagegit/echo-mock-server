package com.echo.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;

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
        // 提交前另一個請求讀到尚未提交的舊資料並放回快取
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

    private static void commit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
    }
}
