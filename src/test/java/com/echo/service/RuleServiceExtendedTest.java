package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.HttpRule;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleServiceExtendedTest {

    @Mock private ProtocolHandlerRegistry protocolHandlerRegistry;
    @Mock private ResponseRepository responseRepository;
    @Mock private Cache<Long, String> responseBodyCache;
    @Mock private CacheConfig cacheConfig;
    @Mock private CacheInvalidationService cacheInvalidationService;
    @Mock private ObjectMapper objectMapper;

    private RuleService ruleService;

    @BeforeEach
    void setUp() {
        ruleService = new RuleService(protocolHandlerRegistry, responseRepository,
                responseBodyCache, cacheConfig, Optional.of(cacheInvalidationService),
                Optional.empty(), objectMapper);
    }

    @Test
    void findResponseBodyById_shouldReturnCached() {
        when(responseBodyCache.getIfPresent(1L)).thenReturn("cached body");

        Optional<String> result = ruleService.findResponseBodyById(1L);

        assertThat(result).contains("cached body");
        verify(responseRepository, never()).findBodyById(any());
    }

    @Test
    void findResponseBodyById_shouldCacheSmallBody() {
        when(responseBodyCache.getIfPresent(1L)).thenReturn(null);
        when(responseRepository.findBodyById(1L)).thenReturn(Optional.of("small body"));
        when(cacheConfig.getBodyCacheThresholdBytes()).thenReturn(1024 * 1024);

        Optional<String> result = ruleService.findResponseBodyById(1L);

        assertThat(result).contains("small body");
        verify(responseBodyCache).put(1L, "small body");
    }

    @Test
    void findResponseBodyById_shouldNotCacheLargeBody() {
        when(responseBodyCache.getIfPresent(1L)).thenReturn(null);
        String largeBody = "x".repeat(1000);
        when(responseRepository.findBodyById(1L)).thenReturn(Optional.of(largeBody));
        when(cacheConfig.getBodyCacheThresholdBytes()).thenReturn(100);

        Optional<String> result = ruleService.findResponseBodyById(1L);

        assertThat(result).contains(largeBody);
        verify(responseBodyCache, never()).put(anyLong(), anyString());
    }

    @Test
    void findResponseBodyById_shouldReturnEmpty_whenNull() {
        Optional<String> result = ruleService.findResponseBodyById(null);

        assertThat(result).isEmpty();
    }

    @Test
    void findResponseBodyById_shouldReturnEmpty_whenNotFound() {
        when(responseBodyCache.getIfPresent(999L)).thenReturn(null);
        when(responseRepository.findBodyById(999L)).thenReturn(Optional.empty());

        Optional<String> result = ruleService.findResponseBodyById(999L);

        assertThat(result).isEmpty();
    }

    @Test
    void evictResponseBodyCache_shouldInvalidate() {
        ruleService.evictResponseBodyCache(1L);
        verify(responseBodyCache).invalidate(1L);
    }

    @Test
    void evictResponseBodyCache_shouldSkipNull() {
        ruleService.evictResponseBodyCache(null);
        verify(responseBodyCache, never()).invalidate(any());
    }

    @Test
    void updateEnabled_shouldReturnZero_whenEmptyList() {
        int count = ruleService.updateEnabled(List.of(), true);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void updateProtected_shouldReturnZero_whenEmptyList() {
        int count = ruleService.updateProtected(List.of(), true);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void updateProtected_shouldPublishInvalidation_whenUpdated() {
        when(protocolHandlerRegistry.updateProtected(anyList(), anyBoolean())).thenReturn(2);

        int count = ruleService.updateProtected(List.of("id1", "id2"), true);

        assertThat(count).isEqualTo(2);
        verify(cacheInvalidationService).publishInvalidation();
    }

    @Test
    void updateProtected_shouldNotPublishInvalidation_whenNoneUpdated() {
        when(protocolHandlerRegistry.updateProtected(anyList(), anyBoolean())).thenReturn(0);

        ruleService.updateProtected(List.of("id1"), true);

        verify(cacheInvalidationService, never()).publishInvalidation();
    }

    @Test
    void extendRule_shouldDelegateToExtendRules() {
        when(protocolHandlerRegistry.extendRules(anyList(), any())).thenReturn(1);

        boolean result = ruleService.extendRule("id1");

        assertThat(result).isTrue();
    }

    @Test
    void extendRules_shouldReturnZero_whenEmptyList() {
        int count = ruleService.extendRules(List.of());
        assertThat(count).isEqualTo(0);
    }

    @Test
    void findIdsByTag_shouldMatchTagPattern() {
        HttpRule rule1 = HttpRule.builder().id("r1").matchKey("/api").method("GET")
                .tags("{\"env\":\"prod\",\"team\":\"A\"}").build();
        HttpRule rule2 = HttpRule.builder().id("r2").matchKey("/api2").method("GET")
                .tags("{\"env\":\"dev\"}").build();

        when(protocolHandlerRegistry.findAllRules()).thenReturn(List.of(rule1, rule2));

        List<String> result = ruleService.findIdsByTag("env", "prod");

        assertThat(result).containsExactly("r1");
    }

    @Test
    void findIdsByTag_shouldReturnEmpty_whenNoMatch() {
        HttpRule rule = HttpRule.builder().id("r1").matchKey("/api").method("GET")
                .tags("{\"env\":\"dev\"}").build();

        when(protocolHandlerRegistry.findAllRules()).thenReturn(List.of(rule));

        List<String> result = ruleService.findIdsByTag("env", "prod");

        assertThat(result).isEmpty();
    }

    @Test
    void findIdsByTag_shouldSkipNullTags() {
        HttpRule rule = HttpRule.builder().id("r1").matchKey("/api").method("GET")
                .tags(null).build();

        when(protocolHandlerRegistry.findAllRules()).thenReturn(List.of(rule));

        List<String> result = ruleService.findIdsByTag("env", "prod");

        assertThat(result).isEmpty();
    }
}
