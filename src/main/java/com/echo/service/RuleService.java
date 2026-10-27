package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.BaseRule;
import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Rules Service - Shared Functions
 * <p>
 * Provide common operations across protocols:
 * <ul>
 *   <li>Response body Cache management</li>
 *   <li>batch enable/disable rule</li>
 *   <li>tag query</li>
 * </ul>
 * 
 * @see HttpRuleService HTTP Rule-specific service
 * @see JmsRuleService JMS Rule-specific service
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleService {

    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ResponseRepository responseRepository;
    private final Cache<Long, VersionedResponseBody> responseBodyCache;
    private final CacheConfig cacheConfig;
    private final Optional<CacheInvalidationService> cacheInvalidationService;
    private final Optional<RuleAuditService> ruleAuditService;
    private final ObjectMapper objectMapper;

    // ==================== Response ====================

    /** Query Response according to ID */
    public Optional<Response> findResponseById(Long id) {
        return responseRepository.findById(id);
    }

    /**
     * Obtain Response body (including cache)
     */
    public Optional<String> findResponseBodyById(Long id) {
        return currentBody(id).map(VersionedResponseBody::body);
    }

    private Optional<VersionedResponseBody> currentBody(Long id) {
        if (id == null) return Optional.empty();
        VersionedResponseBody cached = responseBodyCache.getIfPresent(id);
        if (cached != null) return Optional.of(cached);
        var row = responseRepository.findVersionedBodyById(id);
        row.ifPresent(
                value -> {
                    if (value.body() != null
                            && value.body().length() <= cacheConfig.getBodyCacheThresholdBytes()) {
                        responseBodyCache.put(id, value);
                    }
                });
        return row;
    }

    /** A null legacy incarnation deliberately takes the authoritative read path. */
    public String findResponseBodyById(Long id, ResponseRevision expected) {
        if (id == null) return "";
        var value = currentBody(id);
        if (value.isEmpty() || !value.get().matches(expected)) throw new ResponseRevisionChanged();
        return value.get().body() == null ? "" : value.get().body();
    }

    /** Called only inside the authoritative transaction; never consult or populate a global body cache. */
    public String findUncachedResponseBodyById(Long id) {
        if (id == null) return "";
        return responseRepository
                .findVersionedBodyById(id)
                .map(VersionedResponseBody::body)
                .orElse("");
    }

    /** Clearing body cache for specified Response */
    public void evictResponseBodyCache(Long id) {
        if (id != null) {
            responseBodyCache.invalidate(id);
        }
    }

    // ==================== Batch operation ====================

    /**
     * Enabled status of batch update rule. Only rules that actually changed are updated, recording revisions and triggering rule reconstruction;
     * When everything is in the target state (for example, double-clicking), nothing is done and 0 is returned.
     */
    @Transactional
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true,
            condition = "#result > 0")
    public int updateEnabled(List<String> ids, boolean enabled) {
        if (ids.isEmpty()) {
            return 0;
        }
        
        // Batch record change (one query replaces N+1)
        ruleAuditService.ifPresent(s -> {
            try {
                List<BaseRule> rules = protocolHandlerRegistry.findAllByIds(ids);
                List<String> beforeJsonList = new ArrayList<>();
                List<String> afterJsonList = new ArrayList<>();
                
                for (BaseRule rule : rules) {
                    if (Objects.equals(rule.getEnabled(), enabled)) {
                        continue;
                    }
                    try {
                        // Do not change the managed entity: changes will be written back to the database before the following batch UPDATE,
                        // Make UPDATE think that there are no changes and not trigger rule reconstruction.
                        String beforeJson = objectMapper.writeValueAsString(rule);
                        ObjectNode after = (ObjectNode) objectMapper.readTree(beforeJson);
                        after.put("enabled", enabled);
                        beforeJsonList.add(beforeJson);
                        afterJsonList.add(objectMapper.writeValueAsString(after));
                    } catch (Exception e) {
                        log.warn("Failed to serialize rule {}: {}", rule.getId(), e.getMessage());
                    }
                }
                
                if (!beforeJsonList.isEmpty()) {
                    s.logBatchUpdate(beforeJsonList, afterJsonList);
                }
            } catch (Exception e) {
                log.warn("Failed to log batch enable/disable: {}", e.getMessage());
            }
        });
        
        int count = protocolHandlerRegistry.updateEnabled(ids, enabled);
        if (count > 0) {
            cacheInvalidationService.ifPresent(CacheInvalidationService::publishInvalidation);
        }
        return count;
    }

    /** The protection status is updated in batches; rules that are already in the target status are not updated, and if there are no actual changes, the cache is not cached and other nodes are not notified. */
    @Transactional
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true,
            condition = "#result > 0")
    public int updateProtected(List<String> ids, boolean isProtected) {
        if (ids.isEmpty()) {
            return 0;
        }
        int count = protocolHandlerRegistry.updateProtected(ids, isProtected);
        if (count > 0) {
            cacheInvalidationService.ifPresent(CacheInvalidationService::publishInvalidation);
        }
        return count;
    }

    /**
     * Extending a single rule
     */
    @Transactional
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true,
            condition = "#result")
    public boolean extendRule(String id) {
        return extendRules(List.of(id)) > 0;
    }

    /**
     * Batch extension rules
     */
    @Transactional
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true,
            condition = "#result > 0")
    public int extendRules(List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return protocolHandlerRegistry.extendRules(ids, LocalDateTime.now());
    }

    /**
     * Query rules by tag ID
     */
    public List<String> findIdsByTag(String key, String value) {
        String escapedKey = escapeLikePattern(key);
        String escapedValue = escapeLikePattern(value);
        String pattern = "%\"" + escapedKey + "\":\"" + escapedValue + "\"%";
        return protocolHandlerRegistry.findIdsByTagPattern(pattern);
    }

    private String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
    }

    /**
     * Batch check which responseId exists (shared by HttpRuleService / JmsRuleService)
     */
    public Set<Long> getValidResponseIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptySet();
        }
        if (ids.size() <= MAX_IN_LIST_IDS) {
            return Set.copyOf(responseRepository.findExistingIds(ids));
        }
        // Avoid oversized IN lists: read available primary keys once, then intersect locally.
        // And databases such as SQL Server (2,100 parameters) and Oracle (IN list 1,000 entries) have upper limits.
        Set<Long> existing = new HashSet<>(responseRepository.findAllIds());
        existing.retainAll(ids);
        return Set.copyOf(existing);
    }

    /** More than this number of ID no longer form a single IN list. */
    static final int MAX_IN_LIST_IDS = 500;

}
