package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.BaseRule;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(name = "echo.cleanup.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class CleanupService {
    private static final int RESPONSE_BATCH_SIZE = 100;

    private final ProtocolHandlerRegistry protocolRegistry;
    private final ResponseRepository responseRepository;
    private final CacheManager cacheManager;
    private final ScenarioService scenarioService;
    private final Optional<CacheInvalidationService> cacheInvalidationService;
    private final TransactionTemplate transactions;

    public CleanupService(ProtocolHandlerRegistry protocolRegistry, ResponseRepository responseRepository,
                          CacheManager cacheManager, ScenarioService scenarioService,
                          Optional<CacheInvalidationService> cacheInvalidationService,
                          PlatformTransactionManager transactionManager) {
        this.protocolRegistry = protocolRegistry;
        this.responseRepository = responseRepository;
        this.cacheManager = cacheManager;
        this.scenarioService = scenarioService;
        this.cacheInvalidationService = cacheInvalidationService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Value("${echo.cleanup.rule-retention-days:180}")
    private int ruleRetentionDays;

    @Value("${echo.cleanup.response-retention-days:180}")
    private int responseRetentionDays;

    @Scheduled(cron = "${echo.cleanup.cron:0 0 3 * * *}")
    public void cleanup() {
        log.info("Starting scheduled cleanup...");
        
        LocalDateTime ruleCutoff = LocalDateTime.now().minusDays(ruleRetentionDays);
        LocalDateTime responseCutoff = LocalDateTime.now().minusDays(responseRetentionDays);

        int rulesDeleted = inTransaction(() -> cleanupRules(ruleCutoff));
        long through = inTransaction(responseRepository::findMaximumId);
        long after = 0;
        long responsesDeleted = 0;
        while (after < through && !Thread.currentThread().isInterrupted()) {
            long cursor = after;
            ResponseBatch batch = inTransaction(() -> cleanupResponses(responseCutoff, cursor, through));
            if (batch.lastId() == 0) {
                break;
            }
            after = batch.lastId();
            responsesDeleted += batch.deleted();
        }

        int scenariosDeleted = inTransaction(this::cleanupScenarios);
        log.info("Cleanup completed: {} rules, {} responses, {} orphan scenarios deleted",
                rulesDeleted, responsesDeleted, scenariosDeleted);
    }

    private int cleanupRules(LocalDateTime cutoff) {
        int rulesDeleted = protocolRegistry.deleteExpiredRules(cutoff);

        // 清除快取（交易內的清除會在提交後再清一次）並通知其他實例
        if (rulesDeleted > 0) {
            for (String cacheName : CacheConfig.ALL_RULE_CACHES) {
                var cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            }
            cacheInvalidationService.ifPresent(CacheInvalidationService::publishInvalidation);
        }
        return rulesDeleted;
    }

    private ResponseBatch cleanupResponses(LocalDateTime cutoff, long after, long through) {
        var ids = responseRepository.findOrphanResponseIds(cutoff, after, through,
                PageRequest.of(0, RESPONSE_BATCH_SIZE));
        if (ids.isEmpty()) {
            return new ResponseBatch(0, 0);
        }
        int deleted = responseRepository.deleteExpiredOrphans(ids, cutoff);
        if (deleted > 0) {
            // Publish with this batch's commit. Invalidating rows protected by the delete guard
            // is harmless; a successfully deleted body must never remain cached.
            cacheInvalidationService.ifPresent(s -> s.publishResponsesChanged(ids));
        }
        return new ResponseBatch(ids.get(ids.size() - 1), deleted);
    }

    private int cleanupScenarios() {

        // 清理不再被任何規則引用的 Scenario
        Set<String> activeScenarioNames = protocolRegistry.findAllRules().stream()
                .map(BaseRule::getScenarioName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toSet());
        return scenarioService.deleteOrphans(activeScenarioNames);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()), "Cleanup transaction result");
    }

    private record ResponseBatch(long lastId, int deleted) { }
}
