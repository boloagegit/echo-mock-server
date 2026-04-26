package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.CacheEvent;
import com.echo.entity.Protocol;
import com.echo.repository.CacheEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationServiceTest {

    @Mock private CacheEventRepository eventRepository;
    @Mock private CacheManager cacheManager;
    @Mock private Cache httpCache;
    @Mock private Cache jmsCache;

    private CacheInvalidationService service;

    @BeforeEach
    void setUp() {
        service = new CacheInvalidationService(eventRepository, cacheManager);
    }

    @Test
    void publishInvalidation_shouldSaveGlobalEvent() {
        service.publishInvalidation();

        ArgumentCaptor<CacheEvent> captor = ArgumentCaptor.forClass(CacheEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("RULE_CHANGED");
    }

    @Test
    void publishInvalidation_withHttpProtocol_shouldSaveHttpEvent() {
        service.publishInvalidation(Protocol.HTTP);

        ArgumentCaptor<CacheEvent> captor = ArgumentCaptor.forClass(CacheEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("HTTP_RULE_CHANGED");
    }

    @Test
    void publishInvalidation_withJmsProtocol_shouldSaveJmsEvent() {
        service.publishInvalidation(Protocol.JMS);

        ArgumentCaptor<CacheEvent> captor = ArgumentCaptor.forClass(CacheEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("JMS_RULE_CHANGED");
    }

    @Test
    void checkForInvalidation_withGlobalEvent_shouldClearBothCaches() {
        when(eventRepository.findByTimestampAfter(any())).thenReturn(List.of(new CacheEvent("RULE_CHANGED")));
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(httpCache);
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(jmsCache);

        service.checkForInvalidation();

        verify(httpCache).clear();
        verify(jmsCache).clear();
    }

    @Test
    void checkForInvalidation_withHttpEvent_shouldClearOnlyHttpCache() {
        when(eventRepository.findByTimestampAfter(any())).thenReturn(List.of(new CacheEvent("HTTP_RULE_CHANGED")));
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(httpCache);

        service.checkForInvalidation();

        verify(httpCache).clear();
        verify(cacheManager, never()).getCache(CacheConfig.JMS_RULES_CACHE);
    }

    @Test
    void checkForInvalidation_withJmsEvent_shouldClearOnlyJmsCache() {
        when(eventRepository.findByTimestampAfter(any())).thenReturn(List.of(new CacheEvent("JMS_RULE_CHANGED")));
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(jmsCache);

        service.checkForInvalidation();

        verify(jmsCache).clear();
        verify(cacheManager, never()).getCache(CacheConfig.HTTP_RULES_CACHE);
    }

    @Test
    void checkForInvalidation_shouldNotClearCache_whenNoEvents() {
        when(eventRepository.findByTimestampAfter(any())).thenReturn(List.of());

        service.checkForInvalidation();

        verify(cacheManager, never()).getCache(any());
    }

    @Test
    void cleanup_shouldDeleteOldEvents() {
        when(eventRepository.deleteByTimestampBefore(any(LocalDateTime.class))).thenReturn(5);

        service.cleanup();

        verify(eventRepository).deleteByTimestampBefore(any(LocalDateTime.class));
    }

    @Test
    void checkForInvalidation_shouldUpdateLastChecked() {
        when(eventRepository.findByTimestampAfter(any())).thenReturn(List.of(new CacheEvent("RULE_CHANGED")));
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(httpCache);
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(jmsCache);

        service.checkForInvalidation();
        service.checkForInvalidation();

        verify(eventRepository, times(2)).findByTimestampAfter(any());
    }

    @Test
    void checkForInvalidation_shouldHandleNullCache() {
        when(eventRepository.findByTimestampAfter(any())).thenReturn(List.of(new CacheEvent("RULE_CHANGED")));
        when(cacheManager.getCache(CacheConfig.HTTP_RULES_CACHE)).thenReturn(null);
        when(cacheManager.getCache(CacheConfig.JMS_RULES_CACHE)).thenReturn(null);

        service.checkForInvalidation();

        // 不應拋出例外
        verify(cacheManager).getCache(CacheConfig.HTTP_RULES_CACHE);
        verify(cacheManager).getCache(CacheConfig.JMS_RULES_CACHE);
    }
}
