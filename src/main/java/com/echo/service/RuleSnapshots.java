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
 * HTTP 與 JMS 規則快照（見 {@link RuleSnapshotCache}）：規則或回應變更清除規則快取時，
 * 在一條背景執行緒重建，請求繼續使用舊快照，不必等待。
 */
@Component
@Slf4j
public final class RuleSnapshots {

    /** 單例 key：HTTP 只有一份快照。 */
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

    /** 管理 API 的請求中做出的變更在回應前等待；其他執行緒（多台同步、排程）當下等待。 */
    private static boolean inWebRequest() {
        return RequestContextHolder.getRequestAttributes() != null;
    }

    @PreDestroy
    void shutdown() {
        builder.shutdownNow();
    }
}
