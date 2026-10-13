package com.echo.config;

import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * 清除快取時立即清一次，若在交易內，提交後再清一次。
 *
 * <p>{@code @CacheEvict} 與交易的先後順序不固定；只在提交前清除時，
 * 同時進來的請求可能把尚未提交前的舊資料放回快取。提交後補清可避免這種殘留。</p>
 */
public class CommitAwareCaffeineCacheManager extends CaffeineCacheManager {

    public CommitAwareCaffeineCacheManager(String... cacheNames) {
        super(cacheNames);
    }

    @Override
    protected Cache adaptCaffeineCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> cache) {
        return new CommitAwareCache(name, cache, isAllowNullValues());
    }

    private record Eviction(Cache cache, Object key) {
    }

    private record Clear(Cache cache) {
    }

    static final class CommitAwareCache extends CaffeineCache {

        CommitAwareCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> cache,
                         boolean allowNullValues) {
            super(name, cache, allowNullValues);
        }

        @Override
        public void evict(Object key) {
            super.evict(key);
            AfterCommit.schedule(new Eviction(this, key), () -> super.evict(key));
        }

        @Override
        public boolean evictIfPresent(Object key) {
            boolean present = super.evictIfPresent(key);
            AfterCommit.schedule(new Eviction(this, key), () -> super.evict(key));
            return present;
        }

        @Override
        public void clear() {
            super.clear();
            AfterCommit.schedule(new Clear(this), super::clear);
        }

        @Override
        public boolean invalidate() {
            boolean present = super.invalidate();
            AfterCommit.schedule(new Clear(this), super::clear);
            return present;
        }
    }
}
