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
class CleanupServiceTest {

    @Mock private ProtocolHandlerRegistry protocolRegistry;
    @Mock private ResponseRepository responseRepository;
    @Mock private CacheManager cacheManager;
    @Mock private ScenarioService scenarioService;

    private CleanupService cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new CleanupService(protocolRegistry, responseRepository, cacheManager, scenarioService);
        ReflectionTestUtils.setField(cleanupService, "ruleRetentionDays", 180);
        ReflectionTestUtils.setField(cleanupService, "responseRetentionDays", 90);
    }

    @Test
    void cleanup_shouldPassCorrectCutoffDates() {
        when(protocolRegistry.deleteExpiredRules(any())).thenReturn(0);
        when(responseRepository.findOrphanResponseIds(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now();
        cleanupService.cleanup();

        ArgumentCaptor<LocalDateTime> ruleCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(protocolRegistry).deleteExpiredRules(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue()).isBetween(
                before.minusDays(180).minusSeconds(1), before.minusDays(180).plusSeconds(1));

        ArgumentCaptor<LocalDateTime> responseCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(responseRepository).findOrphanResponseIds(responseCaptor.capture());
        assertThat(responseCaptor.getValue()).isBetween(
                before.minusDays(90).minusSeconds(1), before.minusDays(90).plusSeconds(1));
    }
}
