package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.BaseRule;
import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 規則服務 - 共用功能
 * <p>
 * 提供跨協定的共用操作：
 * <ul>
 *   <li>Response body 快取管理</li>
 *   <li>批次啟用/停用規則</li>
 *   <li>標籤查詢</li>
 * </ul>
 * 
 * @see HttpRuleService HTTP 規則專用服務
 * @see JmsRuleService JMS 規則專用服務
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleService {

    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ResponseRepository responseRepository;
    private final Cache<Long, String> responseBodyCache;
    private final CacheConfig cacheConfig;
    private final Optional<CacheInvalidationService> cacheInvalidationService;
    private final Optional<RuleAuditService> ruleAuditService;
    private final ObjectMapper objectMapper;

    // ==================== Response ====================

    /** 依 ID 查詢 Response */
    public Optional<Response> findResponseById(Long id) {
        return responseRepository.findById(id);
    }

    /**
     * 取得 Response body（含快取）
     */
    public Optional<String> findResponseBodyById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        
        String cached = responseBodyCache.getIfPresent(id);
        if (cached != null) {
            log.debug("Response body cache hit: id={}", id);
            return Optional.of(cached);
        }
        
        Optional<String> body = responseRepository.findBodyById(id);
        body.ifPresent(b -> {
            if (b.length() <= cacheConfig.getBodyCacheThresholdBytes()) {
                responseBodyCache.put(id, b);
                log.debug("Response body cached: id={}, size={}", id, b.length());
            }
        });
        return body;
    }

    /** 清除指定 Response 的 body 快取 */
    public void evictResponseBodyCache(Long id) {
        if (id != null) {
            responseBodyCache.invalidate(id);
        }
    }

    // ==================== 批次操作 ====================

    /**
     * 批次更新規則的啟用狀態
     */
    @Transactional
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true)
    public int updateEnabled(List<String> ids, boolean enabled) {
        if (ids.isEmpty()) {
            return 0;
        }
        
        // 批次記錄變更（一次查詢取代 N+1）
        ruleAuditService.ifPresent(s -> {
            try {
                List<BaseRule> rules = protocolHandlerRegistry.findAllByIds(ids);
                List<String> beforeJsonList = new ArrayList<>();
                List<String> afterJsonList = new ArrayList<>();
                
                for (BaseRule rule : rules) {
                    try {
                        beforeJsonList.add(objectMapper.writeValueAsString(rule));
                        rule.setEnabled(enabled);
                        afterJsonList.add(objectMapper.writeValueAsString(rule));
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

    @Transactional
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true)
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
     * 展延單一規則
     */
    @Transactional
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true)
    public boolean extendRule(String id) {
        return extendRules(List.of(id)) > 0;
    }

    /**
     * 批次展延規則
     */
    @Transactional
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true)
    public int extendRules(List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return protocolHandlerRegistry.extendRules(ids, LocalDateTime.now());
    }

    /**
     * 依標籤查詢規則 ID
     */
    public List<String> findIdsByTag(String key, String value) {
        String pattern = "\"" + key + "\":\"" + value + "\"";
        return protocolHandlerRegistry.findAllRules().stream()
                .filter(r -> r.getTags() != null && matchesTag(r.getTags(), pattern))
                .map(BaseRule::getId)
                .toList();
    }

    /**
     * 批次檢查哪些 responseId 存在（供 HttpRuleService / JmsRuleService 共用）
     */
    public Set<Long> getValidResponseIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptySet();
        }
        return responseRepository.findAllById(ids).stream()
                .map(Response::getId).collect(Collectors.toSet());
    }

    private boolean matchesTag(String tags, String pattern) {
        int idx = tags.indexOf(pattern);
        if (idx < 0) {
            return false;
        }
        int endIdx = idx + pattern.length();
        if (endIdx >= tags.length()) {
            return true;
        }
        char next = tags.charAt(endIdx);
        return next == ',' || next == '}' || next == ' ';
    }
}
