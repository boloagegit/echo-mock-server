package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.config.CommitAwareCaffeineCacheManager;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HTTP and JMS rule snapshots (see {@link RuleSnapshotCache}): When rules or response changes clear the rule cache,
 * Rebuilding in a background thread, requesting to continue using the old snapshot without waiting.
 */
@Component
@Slf4j
public final class RuleSnapshots {

    /** Singleton key: HTTP has only one snapshot. */
    private static final String HTTP_KEY = "http";

    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rule-snapshot-builder");
        thread.setDaemon(true);
        return thread;
    });
    private final RuleSnapshotCache<String, HttpRuleSnapshot> http;
    private final RuleSnapshotCache<String, JmsQueueSnapshot> jms;

    public RuleSnapshots(ObjectProvider<HttpRuleService> httpRuleService,
                         ObjectProvider<JmsRuleService> jmsRuleService,
                         CacheManager cacheManager,
                         @Value("${echo.cache.rules.max-age-minutes:10}") int maxAgeMinutes,
                         @Value("${echo.cache.rules.change-wait-ms:5000}") long changeWaitMillis) {
        long maxAgeNanos = TimeUnit.MINUTES.toNanos(Math.max(1, maxAgeMinutes));
        this.http = new RuleSnapshotCache<>("HTTP", key -> httpRuleService.getObject().buildHttpRuleSnapshot(),
                builder, maxAgeNanos, changeWaitMillis, System::nanoTime);
        this.jms = new RuleSnapshotCache<>("JMS", queue -> jmsRuleService.getObject().buildJmsQueueSnapshot(queue),
                builder, maxAgeNanos, changeWaitMillis, System::nanoTime);
        if (cacheManager instanceof CommitAwareCaffeineCacheManager commitAware) {
            commitAware.onChangeCommitted(CacheConfig.HTTP_RULES_CACHE, () -> http.changed(inWebRequest()));
            commitAware.onChangeCommitted(CacheConfig.JMS_RULES_CACHE, () -> jms.changed(inWebRequest()));
        } else {
            throw new IllegalStateException("Rule snapshots need the commit-aware cache manager to see rule changes");
        }
    }

    public HttpRuleSnapshot http() {
        return http.get(HTTP_KEY);
    }

    JmsQueueSnapshot jms(String queueName) {
        return jms.get(queueName);
    }

    /** The changes made in the request to manage API are waiting before responding; other execution threads (multiple synchronization, scheduling) are currently waiting. */
    private static boolean inWebRequest() {
        return RequestContextHolder.getRequestAttributes() != null;
    }

    @PreDestroy
    void shutdown() {
        builder.shutdownNow();
    }
}
