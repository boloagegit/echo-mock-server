package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CleanupServiceExtendedTest {

    @Mock private ProtocolHandlerRegistry protocolRegistry;
    @Mock private ResponseRepository responseRepository;
    @Mock private CacheManager cacheManager;
    @Mock private Cache httpCache;
    @Mock private Cache jmsCache;
    @Mock private ScenarioService scenarioService;

    private CleanupService cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new CleanupService(protocolRegistry, responseRepository, cacheManager, scenarioService);
        ReflectionTestUtils.setField(cleanupService, "ruleRetentionDays", 180);
        ReflectionTestUtils.setField(cleanupService, "responseRetentionDays", 90);
    }

    @Test
    void cleanup_shouldDeleteExpiredRulesAndOrphanResponses() {
        when(protocolRegistry.deleteExpiredRules(any())).thenReturn(3);
        when(responseRepository.findOrphanResponseIds(any())).thenReturn(List.of(1L, 2L));
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(httpCache);
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(jmsCache);
        when(protocolRegistry.findAllRules()).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now();
        cleanupService.cleanup();

        // Verify cutoff dates are correct
        ArgumentCaptor<LocalDateTime> ruleCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(protocolRegistry).deleteExpiredRules(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue()).isBetween(
                before.minusDays(180).minusSeconds(1), before.minusDays(180).plusSeconds(1));

        ArgumentCaptor<LocalDateTime> responseCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(responseRepository).findOrphanResponseIds(responseCaptor.capture());
        assertThat(responseCaptor.getValue()).isBetween(
                before.minusDays(90).minusSeconds(1), before.minusDays(90).plusSeconds(1));

        verify(responseRepository).deleteAllById(List.of(1L, 2L));
        verify(httpCache).clear();
        verify(jmsCache).clear();
    }

    @Test
    void cleanup_shouldNotClearCache_whenNoRulesDeleted() {
        when(protocolRegistry.deleteExpiredRules(any())).thenReturn(0);
        when(responseRepository.findOrphanResponseIds(any())).thenReturn(List.of());
        when(protocolRegistry.findAllRules()).thenReturn(List.of());

        cleanupService.cleanup();

        verify(cacheManager, never()).getCache(any());
    }

    @Test
    void cleanup_shouldNotDeleteResponses_whenNoOrphans() {
        when(protocolRegistry.deleteExpiredRules(any())).thenReturn(1);
        when(responseRepository.findOrphanResponseIds(any())).thenReturn(List.of());
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(httpCache);
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(jmsCache);
        when(protocolRegistry.findAllRules()).thenReturn(List.of());

        cleanupService.cleanup();

        verify(responseRepository, never()).deleteAllById(any());
    }

    @Test
    void cleanup_shouldHandleNullCache() {
        when(protocolRegistry.deleteExpiredRules(any())).thenReturn(1);
        when(responseRepository.findOrphanResponseIds(any())).thenReturn(List.of());
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(null);
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(null);
        when(protocolRegistry.findAllRules()).thenReturn(List.of());

        cleanupService.cleanup();

        verify(cacheManager).getCache(CacheConfig.HTTP_RULES_CACHE);
        verify(cacheManager).getCache(CacheConfig.JMS_RULES_CACHE);
    }
}
