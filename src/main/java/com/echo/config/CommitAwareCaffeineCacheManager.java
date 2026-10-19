package com.echo.config;

import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Invalidate immediately and, if a transaction is active, invalidate again after commit.
 *
 * <p>The ordering of {@code @CacheEvict} and transaction advice is not fixed. If invalidation runs only before commit,
 * a concurrent request can recache old, uncommitted data. Invalidating again after commit prevents that stale entry from surviving.</p>
 *
 * <p>{@link #onChangeCommitted} invokes registered actions after a confirmed change: once after the transaction commits,
 * or immediately outside a transaction. Rebuild rule snapshots in the background without waiting for another request.</p>
 */
public class CommitAwareCaffeineCacheManager extends CaffeineCacheManager {

    private final Map<String, List<Runnable>> changeListeners = new ConcurrentHashMap<>();

    public CommitAwareCaffeineCacheManager(String... cacheNames) {
        super(cacheNames);
    }

    /** Run {@code listener} after this cache has been invalidated for a change and that change has committed. */
    public void onChangeCommitted(String cacheName, Runnable listener) {
        changeListeners.computeIfAbsent(cacheName, name -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    protected Cache adaptCaffeineCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> cache) {
        return new CommitAwareCache(name, cache, isAllowNullValues(), this);
    }

    private void changeCommitted(String cacheName) {
        List<Runnable> listeners = changeListeners.get(cacheName);
        if (listeners != null) {
            listeners.forEach(Runnable::run);
        }
    }

    private record Eviction(Cache cache, Object key) {
    }

    private record Clear(Cache cache) {
    }

    private record Changed(Cache cache) {
    }

    static final class CommitAwareCache extends CaffeineCache {

        private final CommitAwareCaffeineCacheManager manager;

        CommitAwareCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> cache,
                         boolean allowNullValues, CommitAwareCaffeineCacheManager manager) {
            super(name, cache, allowNullValues);
            this.manager = manager;
        }

        @Override
        public void evict(Object key) {
            super.evict(key);
            AfterCommit.schedule(new Eviction(this, key), () -> super.evict(key));
            changed();
        }

        @Override
        public boolean evictIfPresent(Object key) {
            boolean present = super.evictIfPresent(key);
            AfterCommit.schedule(new Eviction(this, key), () -> super.evict(key));
            changed();
            return present;
        }

        @Override
        public void clear() {
            super.clear();
            AfterCommit.schedule(new Clear(this), super::clear);
            changed();
        }

        @Override
        public boolean invalidate() {
            boolean present = super.invalidate();
            AfterCommit.schedule(new Clear(this), super::clear);
            changed();
            return present;
        }

        /** Inside a transaction, run once after commit and all invalidations; outside a transaction, run immediately. */
        private void changed() {
            Runnable notify = () -> manager.changeCommitted(getName());
            if (!AfterCommit.scheduleLast(new Changed(this), notify)) {
                notify.run();
            }
        }
    }
}
