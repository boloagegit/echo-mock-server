package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import com.echo.service.ScenarioService;
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
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private CleanupService cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new CleanupService(protocolRegistry, responseRepository, cacheManager, scenarioService, java.util.Optional.empty(), transactionManager);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new org.springframework.transaction.support.SimpleTransactionStatus());
        when(responseRepository.findMaximumId()).thenReturn(2L);
        ReflectionTestUtils.setField(cleanupService, "ruleRetentionDays", 180);
        ReflectionTestUtils.setField(cleanupService, "responseRetentionDays", 90);
    }

    @Test
    void cleanup_shouldDeleteExpiredRulesAndOrphanResponses() {
        when(protocolRegistry.deleteExpiredRules(any())).thenReturn(3);
        when(responseRepository.findOrphanResponseIds(any(), anyLong(), anyLong(), any())).thenReturn(List.of(1L, 2L));
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(httpCache);
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(jmsCache);

        LocalDateTime before = LocalDateTime.now();
        cleanupService.cleanup();

        // Verify cutoff dates are correct
        ArgumentCaptor<LocalDateTime> ruleCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(protocolRegistry).deleteExpiredRules(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue()).isBetween(
                before.minusDays(180).minusSeconds(1), before.minusDays(180).plusSeconds(1));

        ArgumentCaptor<LocalDateTime> responseCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(responseRepository).findOrphanResponseIds(responseCaptor.capture(), anyLong(), anyLong(), any());
        assertThat(responseCaptor.getValue()).isBetween(
                before.minusDays(90).minusSeconds(1), before.minusDays(90).plusSeconds(1));

        verify(responseRepository).deleteExpiredOrphans(eq(List.of(1L, 2L)), any());
        verify(httpCache).clear();
        verify(jmsCache).clear();
    }

    @Test
    void cleanup_shouldNotClearCache_whenNoRulesDeleted() {
        when(protocolRegistry.deleteExpiredRules(any())).thenReturn(0);
        when(responseRepository.findOrphanResponseIds(any(), anyLong(), anyLong(), any())).thenReturn(List.of());

        cleanupService.cleanup();

        verify(cacheManager, never()).getCache(any());
    }

    @Test
    void cleanup_shouldNotDeleteResponses_whenNoOrphans() {
        when(protocolRegistry.deleteExpiredRules(any())).thenReturn(1);
        when(responseRepository.findOrphanResponseIds(any(), anyLong(), anyLong(), any())).thenReturn(List.of());
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(httpCache);
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(jmsCache);

        cleanupService.cleanup();

        verify(responseRepository, never()).deleteExpiredOrphans(any(), any());
    }

    @Test
    void cleanup_commitsEachBoundedPageAndContinuesPastRowsProtectedByTheDeleteGuard() {
        when(responseRepository.findMaximumId()).thenReturn(250L);
        var first = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        var second = java.util.stream.LongStream.rangeClosed(101, 200).boxed().toList();
        var last = java.util.stream.LongStream.rangeClosed(201, 250).boxed().toList();
        when(responseRepository.findOrphanResponseIds(any(), eq(0L), eq(250L), any())).thenReturn(first);
        when(responseRepository.findOrphanResponseIds(any(), eq(100L), eq(250L), any())).thenReturn(second);
        when(responseRepository.findOrphanResponseIds(any(), eq(200L), eq(250L), any())).thenReturn(last);
        // Some selected rows can become referenced before the guarded DELETE.
        when(responseRepository.deleteExpiredOrphans(any(), any())).thenReturn(100, 0, 50);
        cleanupService.cleanup();
        var pages = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(responseRepository, times(3)).findOrphanResponseIds(any(), anyLong(), eq(250L), pages.capture());
        assertThat(pages.getAllValues()).allSatisfy(page -> assertThat(page.getPageSize()).isEqualTo(100));
        verify(responseRepository).deleteExpiredOrphans(eq(last), any());
        // Rules, boundary, three response batches, scenarios; no transaction spans the whole run.
        verify(transactionManager, times(6)).commit(any());
    }

    @Test
    void cleanup_shouldHandleNullCache() {
        when(protocolRegistry.deleteExpiredRules(any())).thenReturn(1);
        when(responseRepository.findOrphanResponseIds(any(), anyLong(), anyLong(), any())).thenReturn(List.of());
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(null);
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(null);

        cleanupService.cleanup();

        verify(cacheManager).getCache(CacheConfig.HTTP_RULES_CACHE);
        verify(cacheManager).getCache(CacheConfig.JMS_RULES_CACHE);
    }
}
