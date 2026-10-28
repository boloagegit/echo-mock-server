package com.echo.service;

import com.echo.config.AfterCommit;
import com.echo.config.CacheConfig;
import com.echo.entity.CacheEvent;
import com.echo.entity.Protocol;
import com.echo.protocol.ProtocolDescriptor;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.CacheEventRepository;
import com.github.benmanes.caffeine.cache.Cache;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Synchronize cache invalidation across application instances.
 * Only enabled in Database mode
 *
 * <p>Write a {@code cache_events} event in the same transaction as each data change;
 * instances read events in increasing ID order rather than timestamp order, so clock differences between hosts do not matter.
 * Lower-numbered events may appear later if their transactions commit later; wait {@link #GAP_WAIT_NANOS} for gaps.</p>
 *
 * <p>Local events already invalidated the local cache when changed, with another invalidation after commit; reading them back advances only the checkpoint,
 * avoiding an extra local rule-snapshot rebuild for each change.</p>
 */
@Service
@ConditionalOnProperty(name = "echo.storage.mode", havingValue = "database", matchIfMissing = true)
@ConditionalOnProperty(name = "echo.cache.sync-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class CacheInvalidationService {

    private static final String EVENT_ALL = "RULE_CHANGED";
    /** Protocol event type: protocol name plus this suffix; see {@link ProtocolDescriptor#cacheEventType(Protocol)}. */
    private static final String PROTOCOL_EVENT_SUFFIX = "_RULE_CHANGED";
    /** Single response content change: prefix + response ID. */
    static final String RESPONSE_EVENT_PREFIX = "RESPONSE_CHANGED:";
    /** Multiple or all response content changes. */
    static final String EVENT_ALL_RESPONSES = "RESPONSES_CHANGED";
    /** HTTP forwarding connection setting changes. */
    static final String EVENT_HTTP_TARGETS = "HTTP_TARGET_CHANGED";

    /** Above this many changed responses, emit one all-responses event instead of individual events. */
    static final int MAX_RESPONSE_EVENTS = 50;
    /** Maximum new events read per poll; leave the remainder for the next poll. */
    static final int PAGE_SIZE = 500;
    /** Maximum wait for missing event IDs from transactions that may commit later. */
    static final long GAP_WAIT_NANOS = TimeUnit.MINUTES.toNanos(2);
    /** Treat gaps larger than this as sequence jumps, for example after a database restart, without waiting for each ID. */
    static final int MAX_GAP_RUN = 100;
    /** Maximum missing event IDs tracked concurrently. */
    static final int MAX_GAPS = 1000;
    /** After polling has failed this long, old events may have been pruned; invalidate all caches upon recovery. */
    static final long STALE_AFTER_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int IN_CHUNK = 500;
    static final String STARTUP_WINDOW = "scheduled check after startup (changes another instance committed "
            + "while this one was starting may arrive late)";
    static final String REALIGN_WINDOW = "scheduled check after resuming cache sync (changes committed during "
            + "the pause may arrive late)";

    private final CacheEventRepository eventRepository;
    private final CacheManager cacheManager;
    /** Each {@link ProtocolDescriptor} supplies its protocol's event type and rule-cache name. */
    private final ProtocolHandlerRegistry protocols;

    private final Cache<Long, ?> responseBodyCache;
    private final LongSupplier nanoTime;

    /** Locally emitted event ID to emission time; skip these on readback. Rolled-back IDs never appear and expire from this map. */
    private final Map<Long, Long> ownEvents = new ConcurrentHashMap<>();

    /** Highest processed event ID; null if the database was unavailable at startup, to realign after the next successful read. */
    private Long cursor;
    /** Missing IDs below the cursor, mapped to their wait deadlines. */
    private final TreeMap<Long, Long> gaps = new TreeMap<>();
    /**
     * Gaps too large to track individually trigger a full invalidation at {@code fullClearAt} (nanoTime); further gaps during this wait
     * do not postpone that deadline, but schedule another invalidation at {@code laterClearAt} so clearing cannot be deferred forever.
     */
    private boolean fullClearPending;
    private long fullClearAt;
    private long laterClearAt;
    private String fullClearReason;
    private String laterClearReason;
    private long lastSuccessNanos;
    private long lastFailureLogNanos;
    private boolean failing;

    @Autowired
    public CacheInvalidationService(
            CacheEventRepository eventRepository,
            CacheManager cacheManager,
            ProtocolHandlerRegistry protocols,
            Cache<Long, ?> responseBodyCache) {
        this(eventRepository, cacheManager, protocols, responseBodyCache, System::nanoTime);
    }

    CacheInvalidationService(
            CacheEventRepository eventRepository,
            CacheManager cacheManager,
            ProtocolHandlerRegistry protocols,
            Cache<Long, ?> responseBodyCache,
            LongSupplier nanoTime) {
        this.eventRepository = eventRepository;
        this.cacheManager = cacheManager;
        this.protocols = protocols;
        this.responseBodyCache = responseBodyCache;
        this.nanoTime = nanoTime;
    }

    /**
     * Start at the current maximum event ID; older events cannot affect this newly started, empty cache.
     * Lower-numbered transactions may still be uncommitted and appear later below the starting checkpoint, so invalidate everything again after the late-commit grace period.
     */
    @PostConstruct
    synchronized void start() {
        try {
            cursor = maxEventId();
            lastSuccessNanos = nanoTime.getAsLong();
            scheduleFullClear(lastSuccessNanos + GAP_WAIT_NANOS, STARTUP_WINDOW);
        } catch (RuntimeException e) {
            log.warn("Cache sync could not read cache_events at startup; will retry: {}", e.toString());
        }
    }

    /** Publish global cache invalidation event (clear all rule caches) */
    public void publishInvalidation() {
        publish(EVENT_ALL);
    }

    /** Publishing protocol level cache invalidation event */
    public void publishInvalidation(Protocol protocol) {
        publish(ProtocolDescriptor.cacheEventType(protocol));
    }

    /** These responses changed or were deleted; other instances must evict their cached bodies. */
    public void publishResponsesChanged(Collection<Long> responseIds) {
        Set<Long> ids = new LinkedHashSet<>(responseIds);
        ids.remove(null);
        if (ids.size() > MAX_RESPONSE_EVENTS) {
            publishAllResponsesChanged();
            return;
        }
        ids.forEach(id -> publish(RESPONSE_EVENT_PREFIX + id));
    }

    /** All responses may have changed, for example after deleting all responses. */
    public void publishAllResponsesChanged() {
        publish(EVENT_ALL_RESPONSES);
    }

    /** HTTP Forwarding connection settings have been changed. */
    public void publishHttpTargetsChanged() {
        publish(EVENT_HTTP_TARGETS);
    }

    /**
     * Write each event once per transaction, taking effect only when the transaction commits.
     *
     * <p>Local events are not invalidated again during readback, so invalidate non-rule local caches, such as response bodies and forwarding connections, here.
     * Invalidate again after commit without relying on callers. Rule-change code handles rule-cache invalidation through {@code @CacheEvict} and similar mechanisms,
     * do not clear rule caches again here, which would cause an extra snapshot rebuild.</p>
     */
    private void publish(String eventType) {
        if (AfterCommit.firstInTransaction(new PublishedEvent(this, eventType))) {
            Long id = eventRepository.save(new CacheEvent(eventType)).getId();
            if (id != null) {
                ownEvents.put(id, nanoTime.getAsLong());
                forgetIfRolledBack(id);
            }
        }
        Changes local = new Changes();
        local.add(eventType, protocols.descriptors());
        local.caches.removeAll(CacheConfig.ALL_RULE_CACHES);
        if (!local.isEmpty()) {
            apply(local);
            AfterCommit.schedule(new LocalChange(this, eventType), () -> apply(local));
        }
    }

    /**
     * Forget the ID if its transaction did not commit; the database may reuse it for another instance's event, such as SQLite reusing the highest rowid after rollback.
     * Retaining it could misclassify and ignore another instance's event as a local event.
     */
    private void forgetIfRolledBack(Long id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    ownEvents.remove(id);
                }
            }
        });
    }

    private record PublishedEvent(CacheInvalidationService owner, String eventType) {
    }

    private record LocalChange(CacheInvalidationService owner, String eventType) {
    }

    /** Check regularly for new events */
    @Scheduled(fixedRateString = "${echo.cache.sync-interval-ms:5000}")
    public synchronized void checkForInvalidation() {
        long now = nanoTime.getAsLong();
        try {
            if (cursor == null || now - lastSuccessNanos > STALE_AFTER_NANOS) {
                realign("cache sync resumed after " + (cursor == null ? "startup failure" : "a long pause"));
            } else {
                poll(now);
                runDueFullClear(now);
            }
            lastSuccessNanos = now;
            if (failing) {
                failing = false;
                log.info("Cache sync recovered");
            }
        } catch (RuntimeException e) {
            failing = true;
            if (lastFailureLogNanos == 0 || now - lastFailureLogNanos >= FAILURE_LOG_INTERVAL_NANOS) {
                lastFailureLogNanos = now;
                log.warn("Cache sync poll failed; will retry: {}", e.toString());
            }
        }
    }

    private void poll(long now) {
        List<CacheEvent> events = new ArrayList<>(
                eventRepository.findByIdGreaterThanOrderByIdAsc(cursor, PageRequest.of(0, PAGE_SIZE)));
        if (!gaps.isEmpty()) {
            events.addAll(findGapEvents());
        }
        if (events.isEmpty()) {
            Long max = maxEventId();
            if (max < cursor) {
                // After event-table cleanup, IDs may restart, as in SQLite or older MySQL versions, making previous progress ambiguous.
                realign("cache event ids restarted");
            }
            expireGaps(now);
            expireOwnEvents(now);
            return;
        }

        Changes changes = new Changes();
        long newCursor = cursor;
        events.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        for (CacheEvent event : events) {
            long id = event.getId();
            if (id <= newCursor) {
                if (gaps.remove(id) == null) {
                    continue;
                }
            } else {
                trackGaps(newCursor, id, now);
                newCursor = id;
            }
            if (ownEvents.remove(id) == null) {
                changes.add(event.getEventType(), protocols.descriptors());
            }
        }
        cursor = newCursor;
        expireGaps(now);
        expireOwnEvents(now);
        apply(changes);
        log.debug("Cache invalidated: caches={}, responses={}, events={}",
                changes.caches, changes.allResponses ? "all" : changes.responseIds, events.size());
    }

    private List<CacheEvent> findGapEvents() {
        List<Long> ids = new ArrayList<>(gaps.keySet());
        List<CacheEvent> found = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            found.addAll(eventRepository.findByIdIn(ids.subList(i, Math.min(ids.size(), i + IN_CHUNK))));
        }
        return found;
    }

    private void trackGaps(long previous, long id, long now) {
        long missing = id - previous - 1;
        if (missing <= 0) {
            return;
        }
        long deadline = now + GAP_WAIT_NANOS;
        if (missing > MAX_GAP_RUN || gaps.size() + missing > MAX_GAPS) {
            // The gap is too large to track individually; invalidate everything after late commits should have finished so no event is skipped permanently.
            scheduleFullClear(deadline, "an event id gap was too large to track");
            return;
        }
        for (long gap = previous + 1; gap < id; gap++) {
            gaps.put(gap, deadline);
        }
    }

    private void scheduleFullClear(long deadline, String reason) {
        if (!fullClearPending) {
            fullClearPending = true;
            fullClearAt = deadline;
            laterClearAt = deadline;
            fullClearReason = reason;
            laterClearReason = reason;
        } else if (deadline - laterClearAt > 0) {
            laterClearAt = deadline;
            laterClearReason = reason;
        }
    }

    private void runDueFullClear(long now) {
        if (fullClearPending && now - fullClearAt >= 0) {
            String reason = fullClearReason;
            if (laterClearAt - now > 0) {
                fullClearAt = laterClearAt;
                fullClearReason = laterClearReason;
            } else {
                fullClearPending = false;
            }
            Changes all = new Changes();
            all.markEverything(protocols.descriptors());
            apply(all);
            log.info("Cleared all local caches: {}", reason);
        }
    }

    /** A rolled-back local event never appears; discard its ID after the wait deadline to keep tracking bounded. */
    private void expireOwnEvents(long now) {
        ownEvents.values().removeIf(sentAt -> now - sentAt > 2 * GAP_WAIT_NANOS);
    }

    private void expireGaps(long now) {
        Iterator<Map.Entry<Long, Long>> it = gaps.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > 0) {
                it.remove();
            }
        }
    }

    /** If missed events cannot be identified, invalidate all caches and restart at the current maximum ID. */
    private void realign(String reason) {
        Long max = maxEventId();
        gaps.clear();
        // IDs may have restarted and recorded local IDs may now identify other instances' events; discard them after a full invalidation.
        ownEvents.clear();
        fullClearPending = false;
        cursor = max;
        // As at startup, uncommitted events below max may appear later; schedule another invalidation after the grace period.
        scheduleFullClear(nanoTime.getAsLong() + GAP_WAIT_NANOS, REALIGN_WINDOW);
        Changes all = new Changes();
        all.markEverything(protocols.descriptors());
        apply(all);
        log.info("Cleared all local caches: {}", reason);
    }

    private long maxEventId() {
        Long max = eventRepository.findMaxId();
        return max == null ? 0L : max;
    }

    private void apply(Changes changes) {
        for (String cacheName : changes.caches) {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
        if (changes.allResponses || !changes.responseIds.isEmpty()) {
            for (var descriptor : protocols.descriptors()) {
                var cache = cacheManager.getCache(descriptor.rulesCacheName());
                if (cache != null) cache.clear();
            }
        }
        if (changes.allResponses) {
            responseBodyCache.invalidateAll();
        } else if (!changes.responseIds.isEmpty()) {
            responseBodyCache.invalidateAll(changes.responseIds);
        }
    }

    /** Caches to invalidate during one poll. */
    private static final class Changes {
        private final Set<String> caches = new HashSet<>();
        private final Set<Long> responseIds = new HashSet<>();
        private boolean allResponses;

        void add(String eventType, List<ProtocolDescriptor> enabled) {
            if (eventType != null && eventType.startsWith(RESPONSE_EVENT_PREFIX)) {
                try {
                    responseIds.add(Long.parseLong(eventType.substring(RESPONSE_EVENT_PREFIX.length())));
                } catch (NumberFormatException e) {
                    allResponses = true;
                }
                return;
            }
            if (EVENT_ALL_RESPONSES.equals(eventType)) {
                allResponses = true;
                return;
            }
            if (EVENT_HTTP_TARGETS.equals(eventType)) {
                caches.add(CacheConfig.HTTP_TARGET_DEFAULT_CACHE);
                caches.add(CacheConfig.HTTP_TARGET_RESOLVED_CACHE);
                return;
            }
            caches.addAll(cachesFor(eventType, enabled));
        }

        boolean isEmpty() {
            return caches.isEmpty() && responseIds.isEmpty() && !allResponses;
        }

        void markEverything(List<ProtocolDescriptor> enabled) {
            enabled.forEach(protocol -> caches.add(protocol.rulesCacheName()));
            caches.add(CacheConfig.HTTP_TARGET_DEFAULT_CACHE);
            caches.add(CacheConfig.HTTP_TARGET_RESOLVED_CACHE);
            allResponses = true;
        }
    }

    /**
     * A protocol event invalidates only that protocol's rule cache; do nothing if this instance has the protocol disabled.
     * {@code RULE_CHANGED} or an unknown event invalidates rule caches for every enabled protocol.
     */
    private static Set<String> cachesFor(String eventType, List<ProtocolDescriptor> enabled) {
        for (ProtocolDescriptor protocol : enabled) {
            if (protocol.cacheEventType().equals(eventType)) {
                return Set.of(protocol.rulesCacheName());
            }
        }
        if (eventType != null && !EVENT_ALL.equals(eventType) && eventType.endsWith(PROTOCOL_EVENT_SUFFIX)
                && isProtocolName(eventType.substring(0, eventType.length() - PROTOCOL_EVENT_SUFFIX.length()))) {
            return Set.of();
        }
        Set<String> all = new HashSet<>();
        enabled.forEach(protocol -> all.add(protocol.rulesCacheName()));
        return all;
    }

    private static boolean isProtocolName(String name) {
        for (Protocol protocol : Protocol.values()) {
            if (protocol.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Purge old events every hour */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void cleanup() {
        Long newest = eventRepository.findMaxId();
        if (newest == null) {
            return;
        }
        int deleted = eventRepository.deleteOlderThan(LocalDateTime.now().minusMinutes(10), newest);
        if (deleted > 0) {
            log.debug("Cleaned up {} old cache events", deleted);
        }
    }
}
