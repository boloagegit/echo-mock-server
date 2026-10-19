package com.echo.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
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
        // Simulate cache_events with a list in memory; only "submitted" events are put into table.
        lenient().when(eventRepository.save(any(CacheEvent.class))).thenAnswer(inv -> inv.getArgument(0));
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

    // ========== Release ==========

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

    // ========== Polling ==========

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
        // Old way of using time comparison: events are skipped when publisher clock is slow. Reading by number is not affected.
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
        // Transaction A gets number 1 first but submits it later; transaction B gets number 2 and submits it first.
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
        // Numbers 2–200 have not appeared yet (there may be transactions that have not yet been submitted), 201 appeared first: the vacancies are too large to wait one by one.
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

        // Clear only once
        fillCaches();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
    }

    @Test
    void repeatedLargeGaps_shouldNotPostponeTheFirstClear() {
        commit(1, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        commit(201, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        // Another big gap after one minute: the first clearing time cannot be pushed back
        clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
        commit(401, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        fillCaches();

        clock.addAndGet(TimeUnit.MINUTES.toNanos(1) + 1);
        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).as("first gap's wait is over").isFalse();

        // Clear again when the wait for the second vacancy ends
        fillCaches();
        clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).as("second gap's wait is over").isFalse();
    }

    @Test
    void eventCommittedLateBelowTheStartingPoint_shouldBeCoveredAfterTheWait() {
        // The transaction of another host got the number 5 but has not been submitted yet, 6 is submitted first; this host starts at this time
        commit(6, "JMS_RULE_CHANGED");
        service.start();
        service.checkForInvalidation();
        fillCaches();
        commit(5, "HTTP_RULE_CHANGED");
        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).as("below the cursor, not seen yet").isTrue();

        clock.addAndGet(TimeUnit.MINUTES.toNanos(2) + 1);
        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
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

        // After realignment, new events are processed as usual
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
        // When paused for too long, the event may have been cleaned up, and there is no way to know what was missed.
        clock.addAndGet(TimeUnit.MINUTES.toNanos(6));
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
        assertThat(cached(CacheConfig.HTTP_TARGET_RESOLVED_CACHE)).isFalse();
        assertThat(bodies.estimatedSize()).isZero();
    }

    // ========== Events emitted by this machine ==========

    @Test
    void ownEvents_shouldNotClearLocalCachesAgain() {
        AtomicLong ids = new AtomicLong();
        saveCommits(ids);
        // The local cache has been cleared when the local machine changes; caches that are re-established later do not need to be cleared again because of reading back their own events.
        service.publishInvalidation(Protocol.HTTP);
        service.publishResponsesChanged(List.of(1L));
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
        assertThat(bodies.getIfPresent(1L)).isNotNull();

        // Events for other instances are cleared as usual
        commit(ids.incrementAndGet(), "HTTP_RULE_CHANGED");
        service.checkForInvalidation();
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
    }

    @Test
    void publishing_shouldClearNonRuleCachesLocallyAndAgainAfterCommit() {
        fillCaches();
        TransactionSynchronizationManager.initSynchronization();

        // It is no longer cleared when reading back its own events, so the local response content and forwarding connection cache are cleared when sending out (no need to rely on the caller)
        service.publishResponsesChanged(List.of(1L));
        service.publishHttpTargetsChanged();
        assertThat(bodies.getIfPresent(1L)).isNull();
        assertThat(cached(CacheConfig.HTTP_TARGET_RESOLVED_CACHE)).isFalse();

        // Another request recaches old data before commit; invalidate again after commit.
        bodies.put(1L, "old");
        commitSynchronizations();
        assertThat(bodies.getIfPresent(1L)).isNull();
        assertThat(bodies.getIfPresent(2L)).isNotNull();
    }

    @Test
    void publishingRuleChanges_shouldLeaveRuleCachesToTheCaller() {
        fillCaches();

        service.publishInvalidation();

        // The rule cache is cleared from the change location; clearing it here will cause the rule snapshot to be rebuilt one more time
        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isTrue();
        assertThat(cached(CacheConfig.JMS_RULES_CACHE)).isTrue();
    }

    @Test
    void rolledBackOwnEventId_shouldNotHideAnotherInstancesEventWithTheSameId() {
        commit(1, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        when(eventRepository.save(any(CacheEvent.class))).thenAnswer(inv -> {
            CacheEvent saved = inv.getArgument(0);
            saved.setId(2L);
            return saved;
        });
        TransactionSynchronizationManager.initSynchronization();
        service.publishInvalidation(Protocol.HTTP);
        // Native transaction rollback: SQLite event that may give number 2 to another instance
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationUtils.invokeAfterCompletion(
                synchronizations, org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
        commit(2, "HTTP_RULE_CHANGED");
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
    }

    @Test
    void ownEventIds_shouldBeForgottenWhenIdsRestart() {
        commit(1, "JMS_RULE_CHANGED");
        commit(2, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        // This machine issued number 3 but the transaction was rolled back and not committed.
        when(eventRepository.save(any(CacheEvent.class))).thenAnswer(inv -> {
            CacheEvent saved = inv.getArgument(0);
            saved.setId(3L);
            return saved;
        });
        service.publishInvalidation(Protocol.HTTP);
        // Numbering starts from the beginning after the event table is cleared: number 3 of other instances after that cannot be ignored as a local event
        table.clear();
        service.checkForInvalidation();
        commit(1, "JMS_RULE_CHANGED");
        commit(2, "JMS_RULE_CHANGED");
        commit(3, "HTTP_RULE_CHANGED");
        fillCaches();

        service.checkForInvalidation();

        assertThat(cached(CacheConfig.HTTP_RULES_CACHE)).isFalse();
    }

    // ========== All cleared records ==========

    @Test
    void scheduledClearAfterStartup_shouldBeLoggedAsSuch() {
        clock.addAndGet(CacheInvalidationService.GAP_WAIT_NANOS + 1);

        List<String> logged = infoLogs(service::checkForInvalidation);

        assertThat(logged).containsExactly("Cleared all local caches: " + CacheInvalidationService.STARTUP_WINDOW);
    }

    @Test
    void clearForATooLargeGap_shouldBeLoggedAsSuch() {
        clock.addAndGet(CacheInvalidationService.GAP_WAIT_NANOS + 1);
        service.checkForInvalidation();
        commit(1, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        commit(201, "JMS_RULE_CHANGED");
        service.checkForInvalidation();
        clock.addAndGet(CacheInvalidationService.GAP_WAIT_NANOS + 1);

        List<String> logged = infoLogs(service::checkForInvalidation);

        assertThat(logged).containsExactly("Cleared all local caches: an event id gap was too large to track");
    }

    // ========== Cleanup ==========

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

    /** Writing events are considered submitted, and the numbers are increased sequentially. */
    private void saveCommits(AtomicLong ids) {
        when(eventRepository.save(any(CacheEvent.class))).thenAnswer(inv -> {
            CacheEvent saved = inv.getArgument(0);
            saved.setId(ids.incrementAndGet());
            table.add(saved);
            return saved;
        });
    }

    private static List<String> infoLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(CacheInvalidationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void commitSynchronizations() {
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
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
