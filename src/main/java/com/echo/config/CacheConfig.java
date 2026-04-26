package com.echo.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for Caffeine cache.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String MOCK_RULES_CACHE = "mockRules";
    public static final String HTTP_RULES_CACHE = "httpRules";
    public static final String JMS_RULES_CACHE = "jmsRules";

    /** 所有規則快取名稱（供需要清除全部的場景使用） */
    public static final List<String> ALL_RULE_CACHES = List.of(HTTP_RULES_CACHE, JMS_RULES_CACHE);

    /** Response body 快取上限 (預設 200MB) */
    @Value("${echo.cache.body.max-size-mb:200}")
    private int bodyCacheMaxSizeMb;

    /** 超過此大小的 body 不快取 (預設 500KB) */
    @Value("${echo.cache.body.threshold-kb:500}")
    private int bodyCacheThresholdKb;

    /** Body 快取過期時間 (預設 720 分鐘 = 12 小時) */
    @Value("${echo.cache.body.expire-minutes:720}")
    private int bodyCacheExpireMinutes;

    @Bean
    public Caffeine<Object, Object> caffeineConfig() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(12, TimeUnit.HOURS)
                .recordStats();
    }

    @Bean
    public CacheManager cacheManager(Caffeine<Object, Object> caffeine) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(HTTP_RULES_CACHE, JMS_RULES_CACHE);
        cacheManager.setCaffeine(caffeine);
        return cacheManager;
    }

    /** Response body 快取 - weight-based LRU */
    @Bean
    public Cache<Long, String> responseBodyCache() {
        return Caffeine.newBuilder()
                .maximumWeight((long) bodyCacheMaxSizeMb * 1024 * 1024)
                .weigher((Long id, String body) -> body != null ? body.length() : 0)
                .expireAfterAccess(bodyCacheExpireMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    public int getBodyCacheThresholdBytes() {
        return bodyCacheThresholdKb * 1024;
    }
}
