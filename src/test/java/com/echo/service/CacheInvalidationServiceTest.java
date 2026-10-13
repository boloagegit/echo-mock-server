package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.config.CommitAwareCaffeineCacheManager;
import com.echo.entity.CacheEvent;
import com.echo.entity.Protocol;
import com.echo.protocol.ProtocolDescriptor;
import com.echo.protocol.ProtocolHandler;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.protocol.http.HttpProtocolHandler;
import com.echo.protocol.jms.JmsProtocolHandler;
import com.echo.repository.CacheEventRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationServiceTest {

    @Mock private CacheEventRepository eventRepository;

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final List<CacheEvent> table = new ArrayList<>();
    private CacheManager cacheManager;
    private Cache<Long, String> bodies;
    private CacheInvalidationService service;

    @BeforeEach
    void setUp() {
        cacheManager = new CommitAwareCaffeineCacheManager(CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE,
                CacheConfig.HTTP_TARGET_DEFAULT_CACHE, CacheConfig.HTTP_TARGET_RESOLVED_CACHE);
        bodies = Caffeine.newBuilder().build();
        // 以記憶體中的清單模擬 cache_events；「已提交」的事件才放進 table。
        lenient().when(eventRepository.findMaxId()).thenAnswer(inv ->
                table.stream().mapToLong(CacheEvent::getId).boxed().max(Long::compare).orElse(null));
        lenient().when(eventRepository.findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    long after = inv.getArgument(0);
                    int limit = inv.<Pageable>getArgument(1).getPageSize();
                    return table.stream().filter(e -> e.getId() > after)
                            .sorted((a, b) -> Long.compare(a.getId(), b.getId())).limit(limit).toList();
                });
        lenient().when(eventRepository.findByIdIn(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return table.stream().filter(e -> ids.contains(e.getId())).toList();
        });
        service = create(HttpProtocolHandler.DESCRIPTOR, JmsProtocolHandler.DESCRIPTOR);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private CacheInvalidationService create(ProtocolDescriptor... descriptors) {
        CacheInvalidationService created = new CacheInvalidationService(
                eventRepository, cacheManager, registry(descriptors), bodies, clock::get);
        created.start();
        return created;
    }

    // ========== 發布 ==========

    @Test
    void publishInvalidation_shouldSaveGlobalEvent() {
        service.publishInvalidation();

        assertThat(savedTypes()).containsExactly("RULE_CHANGED");
    }

    @Test
    void publishInvalidation_withProtocol_shouldSaveProtocolEvent() {
        service.publishInvalidation(Protocol.HTTP);
        service.publishInvalidation(Protocol.JMS);

        assertThat(savedTypes()).containsExactly("HTTP_RULE_CHANGED", "JMS_RULE_CHANGED");
    }

    @Test
    void publishResponsesChanged_shouldSaveOneEventPerResponse() {
        service.publishResponsesChanged(List.of(3L, 4L, 3L));

        assertThat(savedTypes()).containsExactly("RESPONSE_CHANGED:3", "RESPONSE_CHANGED:4");
    }

    @Test
    void publishResponsesChanged_manyResponses_shouldSaveOneAllEvent() {
        service.publishResponsesChanged(LongStream.rangeClosed(1, 51).boxed().toList());

        assertThat(savedTypes()).containsExactly("RESPONSES_CHANGED");
    }

    @Test
    void publishHttpTargetsChanged_shouldSaveTargetEvent() {
        service.publishHttpTargetsChanged();

        assertThat(savedTypes()).containsExactly("HTTP_TARGET_CHANGED");
    }

    @Test
    void publish_insideOneTransaction_shouldSaveEachEventTypeOnce() {
        TransactionSynchronizationManager.initSynchronization();

        for (int i = 0; i < 20; i++) {
            service.publishInvalidation(Protocol.HTTP);
        }
        service.publishInvalidation();
        service.publishInvalidation();

        assertThat(savedTypes()).containsExactly("HTTP_RULE_CHANGED", "RULE_CHANGED");
    }

    // ========== 輪詢 ==========

    @Test
    void globalEvent_shouldClearBothRuleCaches() {
        fillCaches();
        commit(1, "RULE_CHANGED");

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.HTTP_TARGET_DEFAULT_CACHE)).isTrue();
        assertThat(bodies.getIfPresent(1L)).isNotNull();
    }

    @Test
    void protocolEvent_shouldClearOnlyThatProtocol() {
        fillCaches();
        commit(1, "HTTP_RULE_CHANGED");

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isTrue();
    }

    @Test
    void eventOfDisabledProtocol_shouldClearNothing() {
        service = create(HttpProtocolHandler.DESCRIPTOR);
        fillCaches();
        commit(1, "JMS_RULE_CHANGED");

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isTrue();
    }

    @Test
    void unknownEvent_shouldClearAllEnabledRuleCaches() {
        service = create(HttpProtocolHandler.DESCRIPTOR);
        fillCaches();
        commit(1, "SOMETHING_ELSE");

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isTrue();
    }

    @Test
    void responseEvent_shouldDropOnlyThatBody() {
        fillCaches();
        commit(1, "RESPONSE_CHANGED:1");

        service.checkForInvalidation();

        assertThat(bodies.getIfPresent(1L)).isNull();
        assertThat(bodies.getIfPresent(2L)).isNotNull();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
    }

    @Test
    void allResponsesEvent_shouldDropAllBodies() {
        fillCaches();
        commit(1, "RESPONSES_CHANGED");

        service.checkForInvalidation();

        assertThat(bodies.estimatedSize()).isZero();
    }

    @Test
    void httpTargetEvent_shouldClearTargetCaches() {
        fillCaches();
        commit(1, "HTTP_TARGET_CHANGED");

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_TARGET_DEFAULT_CACHE)).isFalse();
        assertThat(cached(CacheConfig.HTTP_TARGET_RESOLVED_CACHE)).isFalse();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
    }

    @Test
    void noNewEvents_shouldClearNothing() {
        commit(1, "RULE_CHANGED");
        service = create(HttpProtocolHandler.DESCRIPTOR, JmsProtocolHandler.DESCRIPTOR);
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
    }

    @Test
    void eachEvent_shouldBeAppliedOnlyOnce() {
        commit(1, "HTTP_RULE_CHANGED");
        service.checkForInvalidation();
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
    }

    @Test
    void clockSkew_shouldNotHideEvents() {
        // 舊做法用時間比較：發布端時鐘較慢時事件會被略過。依編號讀取不受影響。
        fillCaches();
        CacheEvent event = new CacheEvent("HTTP_RULE_CHANGED");
        event.setId(1L);
        event.setTimestamp(LocalDateTime.now().minusHours(3));
        table.add(event);

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
    }

    @Test
    void eventCommittedLateWithSmallerId_shouldStillBeApplied() {
        // 交易 A 先拿到編號 1 但較晚提交；交易 B 拿到 2 先提交。
        commit(2, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        fillCaches();
        commit(1, "HTTP_RULE_CHANGED");

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isTrue();
    }

    @Test
    void missingId_shouldBeAppliedOnlyOnceWhenItAppears() {
        commit(2, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        commit(1, "HTTP_RULE_CHANGED");
        service.checkForInvalidation();
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
        verify(eventRepository, times(1)).findByIdIn(any());
    }

    @Test
    void missingId_shouldStopBeingCheckedAfterWaitExpires() {
        commit(2, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        clock.addAndGet(TimeUnit.MINUTES.toNanos(2) + 1);
        service.checkForInvalidation();
        clearInvocations(eventRepository);

        service.checkForInvalidation();

        verify(eventRepository, never()).findByIdIn(any());
    }

    @Test
    void largeIdJump_shouldNotBeTrackedAsMissingEvents() {
        commit(1_001, "JMS_RULE_CHANGED");

        service.checkForInvalidation();
        service.checkForInvalidation();

        verify(eventRepository, never()).findByIdIn(any());
    }

    @Test
    void gapTooLargeToTrack_shouldClearEverythingOnceLateCommitsHadTime() {
        commit(1, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        // 編號 2–200 還沒出現（可能有交易尚未提交），201 先出現：空缺太大，無法逐一等待
        commit(201, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        commit(2, "HTTP_RULE_CHANGED");
        fillCaches();

        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).as("before the wait ends").isTrue();

        clock.addAndGet(TimeUnit.MINUTES.toNanos(2) + 1);
        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).as("after the wait").isFalse();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isFalse();

        // 只清一次
        fillCaches();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
    }

    @Test
    void manyEvents_shouldBeReadAcrossPolls() {
        for (int i = 1; i <= CacheInvalidationService.PAGE_SIZE; i++) {
            commit(i, "JMS_RULE_CHANGED");
        }
        commit(CacheInvalidationService.PAGE_SIZE + 1, "HTTP_RULE_CHANGED");
        service.checkForInvalidation();
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isTrue();
    }

    @Test
    void idsRestartingBelowCursor_shouldClearEverything() {
        commit(500, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        table.clear();
        commit(1, "HTTP_RULE_CHANGED");
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isFalse();
        assertThat(bodies.estimatedSize()).isZero();

        // 重新對齊後，新的事件照常處理
        fillCaches();
        commit(2, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isFalse();
    }

    @Test
    void startupWithoutDatabase_shouldClearEverythingOnFirstSuccessfulPoll() {
        doThrow(new IllegalStateException("db down")).doReturn(4L).when(eventRepository).findMaxId();
        service = create(HttpProtocolHandler.DESCRIPTOR, JmsProtocolHandler.DESCRIPTOR);
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(bodies.estimatedSize()).isZero();
    }

    @Test
    void pollFailures_shouldBeRetried() {
        doThrow(new IllegalStateException("db down"))
                .doReturn(List.of(event(1, "HTTP_RULE_CHANGED")))
                .when(eventRepository).findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class));
        fillCaches();

        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isTrue();
    }

    @Test
    void longOutage_shouldClearEverythingOnRecovery() {
        // 停太久時事件可能已被清理，無法知道漏了什麼。
        clock.addAndGet(TimeUnit.MINUTES.toNanos(6));
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.HTTP_TARGET_RESOLVED_CACHE)).isFalse();
        assertThat(bodies.estimatedSize()).isZero();
    }

    // ========== 清理 ==========

    @Test
    void cleanup_shouldKeepNewestEvent() {
        commit(9, "RULE_CHANGED");
        when(eventRepository.deleteOlderThan(any(LocalDateTime.class), eq(9L))).thenReturn(5);

        service.cleanup();

        verify(eventRepository).deleteOlderThan(any(LocalDateTime.class), eq(9L));
    }

    @Test
    void cleanup_withEmptyTable_shouldDoNothing() {
        service.cleanup();

        verify(eventRepository, never()).deleteOlderThan(any(), any());
    }

    // ========== helpers ==========

    private List<String> savedTypes() {
        ArgumentCaptor<CacheEvent> captor = ArgumentCaptor.forClass(CacheEvent.class);
        verify(eventRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().map(CacheEvent::getEventType).toList();
    }

    private void commit(long id, String type) {
        table.add(event(id, type));
    }

    private static CacheEvent event(long id, String type) {
        CacheEvent event = new CacheEvent(type);
        event.setId(id);
        return event;
    }

    private void fillCaches() {
        for (String name : List.of(CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE,
                CacheConfig.HTTP_TARGET_DEFAULT_CACHE, CacheConfig.HTTP_TARGET_RESOLVED_CACHE)) {
            cacheManager.getCache(name).put("k", "v");
        }
        bodies.put(1L, "one");
        bodies.put(2L, "two");
    }

    private boolean cached(String name) {
        return cacheManager.getCache(name).get("k") != null;
    }

    private static ProtocolHandlerRegistry registry(ProtocolDescriptor... descriptors) {
        List<ProtocolHandler> handlers = new ArrayList<>();
        for (ProtocolDescriptor descriptor : descriptors) {
            ProtocolHandler handler = mock(ProtocolHandler.class);
            lenient().when(handler.getProtocol()).thenReturn(descriptor.id());
            lenient().when(handler.descriptor()).thenReturn(descriptor);
            handlers.add(handler);
        }
        return new ProtocolHandlerRegistry(handlers);
    }
}
