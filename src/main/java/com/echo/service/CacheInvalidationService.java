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
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Cache 同步服務 - 多實例環境下同步 cache 失效
 * 僅在 Database 模式啟用
 *
 * <p>變更時在 {@code cache_events} 寫一筆事件，與資料變更在同一個交易內提交；
 * 各實例依事件編號（不是時間）往後讀，所以不受各台時鐘差異影響。
 * 編號較小的事件可能因交易較晚提交而晚出現，跳過的編號會再等 {@link #GAP_WAIT_NANOS}。</p>
 */
@Service
@ConditionalOnProperty(name = "echo.storage.mode", havingValue = "database", matchIfMissing = true)
@ConditionalOnProperty(name = "echo.cache.sync-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class CacheInvalidationService {

    private static final String EVENT_ALL = "RULE_CHANGED";
    /** 協定事件為「協定名稱 + 此字尾」，見 {@link ProtocolDescriptor#cacheEventType(Protocol)}。 */
    private static final String PROTOCOL_EVENT_SUFFIX = "_RULE_CHANGED";
    /** 單一回應內容變更：前綴 + 回應 ID。 */
    static final String RESPONSE_EVENT_PREFIX = "RESPONSE_CHANGED:";
    /** 多筆或全部回應內容變更。 */
    static final String EVENT_ALL_RESPONSES = "RESPONSES_CHANGED";
    /** HTTP 轉送連線設定變更。 */
    static final String EVENT_HTTP_TARGETS = "HTTP_TARGET_CHANGED";

    /** 一次變更超過這麼多筆回應時，改發一筆「全部回應」事件。 */
    static final int MAX_RESPONSE_EVENTS = 50;
    /** 一次輪詢最多讀幾筆新事件；其餘下一輪再讀。 */
    static final int PAGE_SIZE = 500;
    /** 跳過的編號最多再等多久（等待較晚提交的交易）。 */
    static final long GAP_WAIT_NANOS = TimeUnit.MINUTES.toNanos(2);
    /** 一次跳號超過這個數量時視為序號本身的跳躍（例如資料庫重啟），不逐一等待。 */
    static final int MAX_GAP_RUN = 100;
    /** 同時等待的跳號上限。 */
    static final int MAX_GAPS = 1000;
    /** 超過這麼久沒有成功輪詢時，事件可能已被清掉，恢復後清空所有快取。 */
    static final long STALE_AFTER_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int IN_CHUNK = 500;

    private final CacheEventRepository eventRepository;
    private final CacheManager cacheManager;
    /** 各協定的事件類型與規則快取名稱由其 {@link ProtocolDescriptor} 提供。 */
    private final ProtocolHandlerRegistry protocols;
    private final Cache<Long, String> responseBodyCache;
    private final LongSupplier nanoTime;

    /** 已處理到的最大事件編號；null 表示啟動時讀不到資料庫，下次成功時重新對齊。 */
    private Long cursor;
    /** 比 cursor 小但尚未出現的編號 → 放棄等待的時間點。 */
    private final TreeMap<Long, Long> gaps = new TreeMap<>();
    /**
     * 無法逐一追蹤的編號空缺：到 {@code fullClearAt}（nanoTime）整個清空一次；等待期間又出現的空缺
     * 不延後這次清空，而是在 {@code laterClearAt} 再清一次，不會一直被往後推。
     */
    private boolean fullClearPending;
    private long fullClearAt;
    private long laterClearAt;
    private long lastSuccessNanos;
    private long lastFailureLogNanos;
    private boolean failing;

    @Autowired
    public CacheInvalidationService(CacheEventRepository eventRepository, CacheManager cacheManager,
                                    ProtocolHandlerRegistry protocols, Cache<Long, String> responseBodyCache) {
        this(eventRepository, cacheManager, protocols, responseBodyCache, System::nanoTime);
    }

    CacheInvalidationService(CacheEventRepository eventRepository, CacheManager cacheManager,
                             ProtocolHandlerRegistry protocols, Cache<Long, String> responseBodyCache,
                             LongSupplier nanoTime) {
        this.eventRepository = eventRepository;
        this.cacheManager = cacheManager;
        this.protocols = protocols;
        this.responseBodyCache = responseBodyCache;
        this.nanoTime = nanoTime;
    }

    /**
     * 啟動時從目前最大的事件編號開始；之前的事件與剛啟動、還是空的快取無關。
     * 但編號較小的交易可能還沒提交（之後才出現、又小於起點），所以等晚提交的時間過後再整個清空一次。
     */
    @PostConstruct
    synchronized void start() {
        try {
            cursor = maxEventId();
            lastSuccessNanos = nanoTime.getAsLong();
            scheduleFullClear(lastSuccessNanos + GAP_WAIT_NANOS);
        } catch (RuntimeException e) {
            log.warn("Cache sync could not read cache_events at startup; will retry: {}", e.toString());
        }
    }

    /** 發布全域 cache 失效事件（清除所有規則快取） */
    public void publishInvalidation() {
        publish(EVENT_ALL);
    }

    /** 發布協定級別 cache 失效事件 */
    public void publishInvalidation(Protocol protocol) {
        publish(ProtocolDescriptor.cacheEventType(protocol));
    }

    /** 這些回應的內容已變更或刪除，其他實例要丟掉快取中的內容。 */
    public void publishResponsesChanged(Collection<Long> responseIds) {
        Set<Long> ids = new LinkedHashSet<>(responseIds);
        ids.remove(null);
        if (ids.size() > MAX_RESPONSE_EVENTS) {
            publishAllResponsesChanged();
            return;
        }
        ids.forEach(id -> publish(RESPONSE_EVENT_PREFIX + id));
    }

    /** 全部回應都可能變更（例如全部刪除）。 */
    public void publishAllResponsesChanged() {
        publish(EVENT_ALL_RESPONSES);
    }

    /** HTTP 轉送連線設定已變更。 */
    public void publishHttpTargetsChanged() {
        publish(EVENT_HTTP_TARGETS);
    }

    /** 同一個交易內相同的事件只寫一筆，交易提交時一起生效。 */
    private void publish(String eventType) {
        if (AfterCommit.firstInTransaction(new PublishedEvent(this, eventType))) {
            eventRepository.save(new CacheEvent(eventType));
        }
    }

    private record PublishedEvent(CacheInvalidationService owner, String eventType) {
    }

    /** 定期檢查是否有新事件 */
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
                // 事件表清空後編號從頭開始（例如 SQLite 或舊版 MySQL），已無法分辨哪些看過。
                realign("cache event ids restarted");
            }
            expireGaps(now);
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
            changes.add(event.getEventType(), protocols.descriptors());
        }
        cursor = newCursor;
        expireGaps(now);
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
            // 太多編號無法逐一等待：等晚提交的交易也該完成了，再整個清空一次，不留下永遠跳過的事件
            scheduleFullClear(deadline);
            return;
        }
        for (long gap = previous + 1; gap < id; gap++) {
            gaps.put(gap, deadline);
        }
    }

    private void scheduleFullClear(long deadline) {
        if (!fullClearPending) {
            fullClearPending = true;
            fullClearAt = deadline;
            laterClearAt = deadline;
        } else if (deadline - laterClearAt > 0) {
            laterClearAt = deadline;
        }
    }

    private void runDueFullClear(long now) {
        if (fullClearPending && now - fullClearAt >= 0) {
            if (laterClearAt - now > 0) {
                fullClearAt = laterClearAt;
            } else {
                fullClearPending = false;
            }
            Changes all = new Changes();
            all.markEverything(protocols.descriptors());
            apply(all);
            log.info("Cleared all local caches: an event id gap was too large to track");
        }
    }

    private void expireGaps(long now) {
        Iterator<Map.Entry<Long, Long>> it = gaps.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > 0) {
                it.remove();
            }
        }
    }

    /** 無法確定漏了哪些事件時：清空所有快取，從目前最大的編號重新開始。 */
    private void realign(String reason) {
        Long max = maxEventId();
        gaps.clear();
        fullClearPending = false;
        cursor = max;
        // 同啟動：比 max 小、還沒提交的事件之後才會出現，等待時間過後再清一次
        scheduleFullClear(nanoTime.getAsLong() + GAP_WAIT_NANOS);
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
        if (changes.allResponses) {
            responseBodyCache.invalidateAll();
        } else if (!changes.responseIds.isEmpty()) {
            responseBodyCache.invalidateAll(changes.responseIds);
        }
    }

    /** 一輪輪詢中要清除的快取。 */
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

        void markEverything(List<ProtocolDescriptor> enabled) {
            enabled.forEach(protocol -> caches.add(protocol.rulesCacheName()));
            caches.add(CacheConfig.HTTP_TARGET_DEFAULT_CACHE);
            caches.add(CacheConfig.HTTP_TARGET_RESOLVED_CACHE);
            allResponses = true;
        }
    }

    /**
     * 協定事件只清除該協定的規則快取（本實例未啟用該協定時不需清除）；
     * {@code RULE_CHANGED} 或無法辨識的事件清除所有已啟用協定的規則快取。
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

    /** 每小時清理舊事件 */
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
