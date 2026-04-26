package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandler;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponseServiceTest {

    @Mock
    private ResponseRepository responseRepository;

    @Mock
    private ProtocolHandlerRegistry protocolHandlerRegistry;

    @Mock
    private Cache<Long, String> responseBodyCache;

    @Mock
    @SuppressWarnings("UnusedVariable") // injected via @InjectMocks
    private CacheManager cacheManager;

    @InjectMocks
    private ResponseService responseService;

    // ========== countOrphanResponses ==========

    @Test
    void countOrphanResponses_returnsRepositoryCount() {
        when(responseRepository.countOrphanResponses()).thenReturn(5L);

        long count = responseService.countOrphanResponses();

        assertThat(count).isEqualTo(5L);
        verify(responseRepository).countOrphanResponses();
    }

    @Test
    void countOrphanResponses_returnsZeroWhenNoOrphans() {
        when(responseRepository.countOrphanResponses()).thenReturn(0L);

        long count = responseService.countOrphanResponses();

        assertThat(count).isEqualTo(0L);
    }

    // ========== deleteOrphanResponses ==========

    @Test
    void deleteOrphanResponses_deletesAllOrphans() {
        List<Long> orphanIds = List.of(1L, 2L, 3L);
        when(responseRepository.findAllOrphanResponseIds()).thenReturn(orphanIds);

        int deleted = responseService.deleteOrphanResponses();

        assertThat(deleted).isEqualTo(3);
        verify(responseBodyCache).invalidate(1L);
        verify(responseBodyCache).invalidate(2L);
        verify(responseBodyCache).invalidate(3L);
        verify(responseRepository).deleteAllById(orphanIds);
    }

    @Test
    void deleteOrphanResponses_returnsZeroWhenNoOrphans() {
        when(responseRepository.findAllOrphanResponseIds()).thenReturn(List.of());

        int deleted = responseService.deleteOrphanResponses();

        assertThat(deleted).isEqualTo(0);
        verify(responseRepository, never()).deleteAllById(any());
    }

    // ========== extendResponse ==========

    @Test
    void extendResponse_callsExtendResponses() {
        when(responseRepository.extendResponses(eq(List.of(42L)), any(LocalDateTime.class))).thenReturn(1);

        boolean result = responseService.extendResponse(42L);

        assertThat(result).isTrue();
        verify(responseRepository).extendResponses(eq(List.of(42L)), any(LocalDateTime.class));
    }

    @Test
    void extendResponse_returnsFalseWhenNotFound() {
        when(responseRepository.extendResponses(eq(List.of(999L)), any(LocalDateTime.class))).thenReturn(0);

        boolean result = responseService.extendResponse(999L);

        assertThat(result).isFalse();
    }

    // ========== extendResponses ==========

    @Test
    void extendResponses_batchUpdate() {
        List<Long> ids = List.of(1L, 2L, 3L);
        when(responseRepository.extendResponses(eq(ids), any(LocalDateTime.class))).thenReturn(3);

        int updated = responseService.extendResponses(ids);

        assertThat(updated).isEqualTo(3);
        verify(responseRepository).extendResponses(eq(ids), any(LocalDateTime.class));
    }

    @Test
    void extendResponses_emptyListReturnsZero() {
        int updated = responseService.extendResponses(List.of());

        assertThat(updated).isEqualTo(0);
        verify(responseRepository, never()).extendResponses(any(), any());
    }

    // ========== findAllWithUsage includes extendedAt ==========

    @Test
    void findAllWithUsage_includesExtendedAt() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime extended = now.plusDays(10);
        Object[] row = new Object[]{1L, "desc", 100, "TEXT", now, now, extended};
        List<Object[]> summaryList = new ArrayList<>();
        summaryList.add(row);
        when(responseRepository.findAllSummary()).thenReturn(summaryList);
        ProtocolHandler mockHandler = mock(ProtocolHandler.class);
        when(mockHandler.countGroupByResponseId()).thenReturn(new ArrayList<Object[]>());
        when(protocolHandlerRegistry.getAllHandlers()).thenReturn(List.of(mockHandler));

        List<ResponseService.ResponseSummary> summaries = responseService.findAllWithUsage();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getExtendedAt()).isEqualTo(extended);
        assertThat(summaries.get(0).getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void findAllWithUsage_extendedAtCanBeNull() {
        LocalDateTime now = LocalDateTime.now();
        Object[] row = new Object[]{1L, "desc", 100, "TEXT", now, now, null};
        List<Object[]> summaryList = new ArrayList<>();
        summaryList.add(row);
        when(responseRepository.findAllSummary()).thenReturn(summaryList);
        ProtocolHandler mockHandler2 = mock(ProtocolHandler.class);
        when(mockHandler2.countGroupByResponseId()).thenReturn(new ArrayList<Object[]>());
        when(protocolHandlerRegistry.getAllHandlers()).thenReturn(List.of(mockHandler2));

        List<ResponseService.ResponseSummary> summaries = responseService.findAllWithUsage();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getExtendedAt()).isNull();
    }
}
