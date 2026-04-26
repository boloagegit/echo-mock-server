package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.CacheEvent;
import com.echo.entity.Protocol;
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
@RequiredArgsConstructor
@Slf4j
public class CacheInvalidationService {

    private static final String EVENT_HTTP = "HTTP_RULE_CHANGED";
    private static final String EVENT_JMS = "JMS_RULE_CHANGED";
    private static final String EVENT_ALL = "RULE_CHANGED";

    private final CacheEventRepository eventRepository;
    private final CacheManager cacheManager;

    private volatile LocalDateTime lastChecked = LocalDateTime.now();

    /** 發布全域 cache 失效事件（清除所有規則快取） */
    public void publishInvalidation() {
        eventRepository.save(new CacheEvent(EVENT_ALL));
    }

    /** 發布協定級別 cache 失效事件 */
    public void publishInvalidation(Protocol protocol) {
        String eventType = (protocol == Protocol.JMS) ? EVENT_JMS : EVENT_HTTP;
        eventRepository.save(new CacheEvent(eventType));
    }

    /** 定期檢查是否有新事件 */
    @Scheduled(fixedRateString = "${echo.cache.sync-interval-ms:5000}")
    public void checkForInvalidation() {
        List<CacheEvent> events = eventRepository.findByTimestampAfter(lastChecked);
        if (!events.isEmpty()) {
            Set<String> cachesToClear = new HashSet<>();
            for (CacheEvent event : events) {
                switch (event.getEventType()) {
                    case EVENT_HTTP -> cachesToClear.add(CacheConfig.HTTP_RULES_CACHE);
                    case EVENT_JMS -> cachesToClear.add(CacheConfig.JMS_RULES_CACHE);
                    default -> {
                        // RULE_CHANGED 或其他 → 清除全部
                        cachesToClear.add(CacheConfig.HTTP_RULES_CACHE);
                        cachesToClear.add(CacheConfig.JMS_RULES_CACHE);
                    }
                }
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
