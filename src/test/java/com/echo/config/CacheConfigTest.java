package com.echo.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigTest {

    @Test
    void caffeineConfig_shouldCreateCaffeine() {
        CacheConfig config = new CacheConfig();
        var caffeine = config.caffeineConfig();
        assertThat(caffeine).isNotNull();
    }

    @Test
    void cacheManager_shouldCreateManagerWithBothCaches() {
        CacheConfig config = new CacheConfig();
        var caffeine = config.caffeineConfig();
        CacheManager manager = config.cacheManager(caffeine);
        
        assertThat(manager).isNotNull();
        assertThat(manager.getCacheNames())
                .contains(CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE);
    }

    @Test
    void cacheConstants_shouldExist() {
        assertThat(CacheConfig.HTTP_RULES_CACHE).isEqualTo("httpRules");
        assertThat(CacheConfig.JMS_RULES_CACHE).isEqualTo("jmsRules");
        assertThat(CacheConfig.ALL_RULE_CACHES).containsExactly("httpRules", "jmsRules");
    }

    @Test
    void cacheManager_shouldReturnHttpRulesCache() {
        CacheConfig config = new CacheConfig();
        var caffeine = config.caffeineConfig();
        CacheManager manager = config.cacheManager(caffeine);
        
        assertThat(manager.getCache(CacheConfig.HTTP_RULES_CACHE)).isNotNull();
    }

    @Test
    void cacheManager_shouldReturnJmsRulesCache() {
        CacheConfig config = new CacheConfig();
        var caffeine = config.caffeineConfig();
        CacheManager manager = config.cacheManager(caffeine);
        
        assertThat(manager.getCache(CacheConfig.JMS_RULES_CACHE)).isNotNull();
    }

    @Test
    void mockRulesCache_legacyConstantShouldExist() {
        assertThat(CacheConfig.MOCK_RULES_CACHE).isEqualTo("mockRules");
    }
}
