package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.BaseRule;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "echo.cleanup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CleanupService {

    private final ProtocolHandlerRegistry protocolRegistry;
    private final ResponseRepository responseRepository;
    private final CacheManager cacheManager;
    private final ScenarioService scenarioService;

    @Value("${echo.cleanup.rule-retention-days:180}")
    private int ruleRetentionDays;

    @Value("${echo.cleanup.response-retention-days:180}")
    private int responseRetentionDays;

    @Scheduled(cron = "${echo.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanup() {
        log.info("Starting scheduled cleanup...");
        
        LocalDateTime ruleCutoff = LocalDateTime.now().minusDays(ruleRetentionDays);
        LocalDateTime responseCutoff = LocalDateTime.now().minusDays(responseRetentionDays);

        // 清除舊規則（跨協定）
        int rulesDeleted = protocolRegistry.deleteExpiredRules(ruleCutoff);
        
        // 直接在 DB 查詢未被使用且過期的 Response ID（避免全表載入）
        List<Long> toDelete = responseRepository.findOrphanResponseIds(responseCutoff);
        
        if (!toDelete.isEmpty()) {
            responseRepository.deleteAllById(toDelete);
        }

        // 清除快取
        if (rulesDeleted > 0) {
            for (String cacheName : CacheConfig.ALL_RULE_CACHES) {
                var cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            }
        }

        // 清理不再被任何規則引用的 Scenario
        Set<String> activeScenarioNames = protocolRegistry.findAllRules().stream()
                .map(BaseRule::getScenarioName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toSet());
        int scenariosDeleted = scenarioService.deleteOrphans(activeScenarioNames);

        log.info("Cleanup completed: {} rules, {} responses, {} orphan scenarios deleted",
                rulesDeleted, toDelete.size(), scenariosDeleted);
    }
}
