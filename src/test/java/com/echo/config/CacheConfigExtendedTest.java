package com.echo.config;

import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigExtendedTest {

    @Test
    void ruleCache_shouldRemainBoundedUnderDynamicPathChurn() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "ruleCacheMaxEntries", 128);
        ReflectionTestUtils.setField(config, "ruleCacheExpireMinutes", 60);
        var manager = config.cacheManager(config.caffeineConfig());
        CaffeineCache springCache = (CaffeineCache) manager.getCache(CacheConfig.HTTP_RULES_CACHE);
        assertThat(springCache).isNotNull();
        Cache<Object, Object> nativeCache = springCache.getNativeCache();

        for (int i = 0; i < 10_000; i++) {
            nativeCache.put("http:host:/orders/" + i + ":GET", "candidate-list");
        }
        nativeCache.cleanUp();

        assertThat(nativeCache.estimatedSize()).isLessThanOrEqualTo(128);
        assertThat(nativeCache.stats().evictionCount()).isGreaterThan(0);
    }

    @Test
    void ruleCacheSettings_shouldExposeConfiguredBounds() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "ruleCacheMaxEntries", 321);
        ReflectionTestUtils.setField(config, "ruleCacheExpireMinutes", 45);

        assertThat(config.getRuleCacheMaxEntries()).isEqualTo(321);
        assertThat(config.getRuleCacheExpireMinutes()).isEqualTo(45);
    }

    @Test
    void responseBodyCache_shouldCreateWeightBasedCache() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "bodyCacheMaxSizeMb", 100);
        ReflectionTestUtils.setField(config, "bodyCacheThresholdKb", 500);
        ReflectionTestUtils.setField(config, "bodyCacheExpireMinutes", 30);

        Cache<Long, String> cache = config.responseBodyCache();

        assertThat(cache).isNotNull();
        cache.put(1L, "test body");
        assertThat(cache.getIfPresent(1L)).isEqualTo("test body");
    }

    @Test
    void responseBodyCache_shouldEvictByWeight() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "bodyCacheMaxSizeMb", 1);
        ReflectionTestUtils.setField(config, "bodyCacheThresholdKb", 500);
        ReflectionTestUtils.setField(config, "bodyCacheExpireMinutes", 30);

        Cache<Long, String> cache = config.responseBodyCache();

        String largeBody = "x".repeat(512 * 1024);
        cache.put(1L, largeBody);
        cache.put(2L, largeBody);
        cache.put(3L, largeBody);
        cache.cleanUp();

        long size = cache.estimatedSize();
        assertThat(size).isLessThanOrEqualTo(3);
    }

    @Test
    void getBodyCacheThresholdBytes_shouldConvertKbToBytes() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "bodyCacheThresholdKb", 500);

        assertThat(config.getBodyCacheThresholdBytes()).isEqualTo(500 * 1024);
    }

    @Test
    void getBodyCacheThresholdBytes_shouldHandleCustomValue() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "bodyCacheThresholdKb", 1024);

        assertThat(config.getBodyCacheThresholdBytes()).isEqualTo(1024 * 1024);
    }

    @Test
    void responseBodyCache_shouldHandleNullValue() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "bodyCacheMaxSizeMb", 100);
        ReflectionTestUtils.setField(config, "bodyCacheThresholdKb", 500);
        ReflectionTestUtils.setField(config, "bodyCacheExpireMinutes", 30);

        Cache<Long, String> cache = config.responseBodyCache();

        cache.put(1L, "value");
        assertThat(cache.getIfPresent(1L)).isEqualTo("value");
        assertThat(cache.getIfPresent(999L)).isNull();
    }
}
