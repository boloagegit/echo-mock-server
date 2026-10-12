package com.echo.service;

import com.echo.entity.CacheEvent;
import com.echo.entity.Protocol;
import com.echo.protocol.ProtocolDescriptor;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.CacheEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cache 同步服務 - 多實例環境下同步 cache 失效
 * 僅在 Database 模式啟用
 */
@Service
@ConditionalOnProperty(name = "echo.storage.mode", havingValue = "database", matchIfMissing = true)
@ConditionalOnProperty(name = "echo.cache.sync-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CacheInvalidationService {

    private static final String EVENT_ALL = "RULE_CHANGED";
    /** 協定事件為「協定名稱 + 此字尾」，見 {@link ProtocolDescriptor#cacheEventType(Protocol)}。 */
    private static final String PROTOCOL_EVENT_SUFFIX = "_RULE_CHANGED";

    private final CacheEventRepository eventRepository;
    private final CacheManager cacheManager;
    /** 各協定的事件類型與規則快取名稱由其 {@link ProtocolDescriptor} 提供。 */
    private final ProtocolHandlerRegistry protocols;

    private volatile LocalDateTime lastChecked = LocalDateTime.now();

    /** 發布全域 cache 失效事件（清除所有規則快取） */
    public void publishInvalidation() {
        eventRepository.save(new CacheEvent(EVENT_ALL));
    }

    /** 發布協定級別 cache 失效事件 */
    public void publishInvalidation(Protocol protocol) {
        eventRepository.save(new CacheEvent(ProtocolDescriptor.cacheEventType(protocol)));
    }

    /** 定期檢查是否有新事件 */
    @Scheduled(fixedRateString = "${echo.cache.sync-interval-ms:5000}")
    public void checkForInvalidation() {
        List<CacheEvent> events = eventRepository.findByTimestampAfter(lastChecked);
        if (!events.isEmpty()) {
            Set<String> cachesToClear = new HashSet<>();
            List<ProtocolDescriptor> enabled = protocols.descriptors();
            for (CacheEvent event : events) {
                cachesToClear.addAll(cachesFor(event.getEventType(), enabled));
            }
            for (String cacheName : cachesToClear) {
                var cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            }
            log.debug("Cache invalidated: caches={}, events={}", cachesToClear, events.size());
            lastChecked = LocalDateTime.now();
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
        int deleted = eventRepository.deleteByTimestampBefore(LocalDateTime.now().minusMinutes(10));
        if (deleted > 0) {
            log.debug("Cleaned up {} old cache events", deleted);
        }
    }
}
