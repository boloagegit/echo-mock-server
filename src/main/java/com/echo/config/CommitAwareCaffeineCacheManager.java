package com.echo.config;

import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 清除快取時立即清一次，若在交易內，提交後再清一次。
 *
 * <p>{@code @CacheEvict} 與交易的先後順序不固定；只在提交前清除時，
 * 同時進來的請求可能把尚未提交前的舊資料放回快取。提交後補清可避免這種殘留。</p>
 *
 * <p>{@link #onChangeCommitted} 註冊的動作在資料確定變更時執行：交易提交後（同一交易內只一次），
 * 不在交易內則立即執行。規則快照用它在背景重建，不必等請求來觸發。</p>
 */
public class CommitAwareCaffeineCacheManager extends CaffeineCacheManager {

    private final Map<String, List<Runnable>> changeListeners = new ConcurrentHashMap<>();

    public CommitAwareCaffeineCacheManager(String... cacheNames) {
        super(cacheNames);
    }

    /** 這個快取被清除（資料已變更）且變更已提交時執行 {@code listener}。 */
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

        /** 交易內：提交後執行一次（排在所有補清之後）；不在交易內：立即執行。 */
        private void changed() {
            Runnable notify = () -> manager.changeCommitted(getName());
            if (!AfterCommit.scheduleLast(new Changed(this), notify)) {
                notify.run();
            }
        }
    }
}
