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
    public static final String HTTP_TARGET_DEFAULT_CACHE = "httpTargetDefault";
    public static final String HTTP_TARGET_RESOLVED_CACHE = "httpTargetResolved";

    /** All rule cache names (for use in scenarios where all needs to be cleared) */
    public static final List<String> ALL_RULE_CACHES = List.of(HTTP_RULES_CACHE, JMS_RULES_CACHE);

    /** Maximum request-derived keys in the rule-query cache, preventing dynamic URLs from growing the heap without bound. */
    @Value("${echo.cache.rules.max-entries:2000}")
    private int ruleCacheMaxEntries = 2000;

    /** Rule query cache idle timeout minutes. */
    @Value("${echo.cache.rules.expire-minutes:60}")
    private int ruleCacheExpireMinutes = 60;

    /**
     * Maximum cache lifetime in minutes for rules and connection settings, regardless of access frequency.
     * This bounds the delay before reloading if a change notification is missed across multiple deployments.
     */
    @Value("${echo.cache.rules.max-age-minutes:10}")
    private int ruleCacheMaxAgeMinutes = 10;

    /** Response body cache limit (default 200MB) */
    @Value("${echo.cache.body.max-size-mb:200}")
    private int bodyCacheMaxSizeMb;

    /** body larger than this size is not cached (default 500KB) */
    @Value("${echo.cache.body.threshold-kb:500}")
    private int bodyCacheThresholdKb;

    /** Body cache expiration time (default 720 minutes = 12 hours) */
    @Value("${echo.cache.body.expire-minutes:720}")
    private int bodyCacheExpireMinutes;

    /** Maximum body-cache lifetime in minutes, regardless of access frequency, as a fallback for missed change notifications. */
    @Value("${echo.cache.body.max-age-minutes:60}")
    private int bodyCacheMaxAgeMinutes = 60;

    @Bean
    public Caffeine<Object, Object> caffeineConfig() {
        return Caffeine.newBuilder()
                .maximumSize(Math.max(1, ruleCacheMaxEntries))
                .expireAfterAccess(Math.max(1, ruleCacheExpireMinutes), TimeUnit.MINUTES)
                .expireAfterWrite(Math.max(1, ruleCacheMaxAgeMinutes), TimeUnit.MINUTES)
                .recordStats();
    }

    @Bean
    public CacheManager cacheManager(Caffeine<Object, Object> caffeine) {
        CaffeineCacheManager cacheManager = new CommitAwareCaffeineCacheManager(
                HTTP_RULES_CACHE, JMS_RULES_CACHE, HTTP_TARGET_DEFAULT_CACHE,
                HTTP_TARGET_RESOLVED_CACHE);
        cacheManager.setCaffeine(caffeine);
        return cacheManager;
    }

    /** Response body cache - weight-based LRU */
    @Bean
    public Cache<Long, String> responseBodyCache() {
        return Caffeine.newBuilder()
                .maximumWeight((long) Math.max(1, bodyCacheMaxSizeMb) * 1024 * 1024)
                .weigher((Long id, String body) -> estimateBodyWeight(body))
                .expireAfterAccess(Math.max(1, bodyCacheExpireMinutes), TimeUnit.MINUTES)
                .expireAfterWrite(Math.max(1, bodyCacheMaxAgeMinutes), TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /**
     * Caffeine weights are relative capacity estimates, not exact retained-heap measurements.
     * Count UTF-16 storage plus conservative String/cache-entry overhead so non-ASCII and
     * large response bodies cannot consume roughly twice the configured byte budget.
     */
    static int estimateBodyWeight(String body) {
        if (body == null) return 1;
        long estimatedBytes = 96L + (long) body.length() * Character.BYTES;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, estimatedBytes));
    }

    public int getBodyCacheThresholdBytes() {
        return bodyCacheThresholdKb * 1024;
    }

    public int getRuleCacheMaxEntries() {
        return ruleCacheMaxEntries;
    }

    public int getRuleCacheExpireMinutes() {
        return ruleCacheExpireMinutes;
    }

    public int getRuleCacheMaxAgeMinutes() {
        return ruleCacheMaxAgeMinutes;
    }
}
