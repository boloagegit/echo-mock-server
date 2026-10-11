package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.core.engine.RuleSelector;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.protocol.http.HttpProtocolHandler;
import com.echo.repository.HttpRuleRepository;
import com.echo.util.CurrentOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTTP 規則服務
 * <p>
 * 負責 HTTP 規則的 CRUD 操作與請求匹配邏輯。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HttpRuleService {

    private final HttpProtocolHandler httpHandler;
    private final RuleService ruleService;
    private final HttpRuleRepository httpRuleRepository;
    private final ConditionMatcher conditionMatcher;
    private final Optional<CacheInvalidationService> cacheInvalidationService;
    private final ApplicationContext applicationContext;

    private HttpRuleService proxy() {
        if (applicationContext == null) {
            return this;
        }
        return applicationContext.getBean(HttpRuleService.class);
    }

    @Cacheable(cacheNames = CacheConfig.HTTP_RULES_CACHE,
               key = "'http:' + #host + ':' + #path + ':' + #method")
    public List<HttpRule> findHttpRules(String host, String path, String method) {
        log.debug("Cache miss - querying HTTP rules: host={}, path={}, method={}", host, path, method);
        return httpHandler.findWithFallback(host, path, method);
    }

    public MatchResult<HttpRule> findMatchingHttpRuleWithCandidates(String host, String path, String method,
            String body, String queryString, Map<String, String> headers) {
        List<HttpRule> rules = findPreparedHttpRules(host, path, method);
        return findMatchingHttpRuleInternal(rules, body, queryString, headers);
    }

    /**
     * 從 host + method 的共用規則集篩出本次 path 候選。
     * 動態 URL（例如 /users/1、/users/2）不再各自占用一個 Caffeine entry。
     */
    public List<HttpRule> findPreparedHttpRules(String host, String path, String method) {
        // 直接建構 service 的單元測試／工具沒有 Spring proxy，維持原有查詢路徑。
        if (applicationContext == null) {
            return prepareHttpRules(httpHandler.findWithFallback(host, path, method));
        }
        return proxy().httpRuleSnapshot().candidates(host, method).stream()
                .filter(rule -> httpHandler.matchesRequestPath(rule.getMatchKey(), path))
                .toList();
    }

    /** host + method 的候選規則（已排序、未依路徑篩選），取自目前的規則快照。 */
    public List<HttpRule> findPreparedHttpRuleSet(String host, String method) {
        return proxy().httpRuleSnapshot().candidates(host, method);
    }

    /**
     * 目前的 HTTP 規則快照。存放在 {@code httpRules} 快取的單一項目，任何規則或 Response 異動清除快取時一併淘汰；
     * {@code sync = true} 讓並行請求只建立一次。
     */
    @Cacheable(cacheNames = CacheConfig.HTTP_RULES_CACHE, key = "'http-snapshot'", sync = true)
    public HttpRuleSnapshot httpRuleSnapshot() {
        List<HttpRule> rules = httpRuleRepository.findByEnabledTrueOrderByIdAsc();
        Set<Long> validResponseIds = ruleService.getValidResponseIds(rules.stream()
                .map(HttpRule::getResponseId).filter(id -> id != null).collect(Collectors.toSet()));
        List<HttpRule> valid = rules.stream()
                .filter(r -> r.getResponseId() == null || validResponseIds.contains(r.getResponseId()))
                .toList();
        log.debug("Building HTTP rule snapshot: {} enabled rules", valid.size());
        return new HttpRuleSnapshot(valid, HTTP_RULE_ORDER, conditionMatcher);
    }

    /** 候選規則優先順序：非萬用路徑 → priority 高 → 有指定 host → 建立時間新。 */
    static final Comparator<HttpRule> HTTP_RULE_ORDER = Comparator
            .comparing((HttpRule r) -> "*".equals(r.getMatchKey()) ? 1 : 0)
            .thenComparing(Comparator.<HttpRule, Integer>comparing(
                    r -> r.getPriority() != null ? r.getPriority() : Integer.MIN_VALUE).reversed())
            .thenComparing(r -> r.getTargetHost() == null || r.getTargetHost().isEmpty() ? 1 : 0)
            .thenComparing(HttpRule::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private List<HttpRule> prepareHttpRules(List<HttpRule> rules) {
        if (rules.isEmpty()) {
            return rules;
        }
        Set<Long> validResponseIds = ruleService.getValidResponseIds(rules.stream()
                .map(HttpRule::getResponseId).filter(id -> id != null).collect(Collectors.toSet()));
        return rules.stream()
                .filter(r -> r.getResponseId() == null || validResponseIds.contains(r.getResponseId()))
                .sorted(HTTP_RULE_ORDER)
                .toList();
    }

    private MatchResult<HttpRule> findMatchingHttpRuleInternal(List<HttpRule> rules, String body,
            String queryString, Map<String, String> headers) {
        ConditionMatcher.PreparedBody prepared = conditionMatcher.prepareBody(body);
        HttpRule matched = RuleSelector.select(rules, RuleSelector.FallbackPolicy.FIRST_UNCONDITIONAL,
                HTTP_RULE_TRAITS, rule -> true,
                rule -> conditionMatcher.matchesPrepared(rule.getBodyCondition(), rule.getQueryCondition(),
                        rule.getHeaderCondition(), prepared, queryString, headers))
                .selected();

        List<MatchChainEntry> chain = matched != null
                ? List.of(MatchChainEntry.fromHttp(matched, "match"))
                : List.of();
        return new MatchResult<>(matched, chain);
    }

    private static final RuleSelector.Traits<HttpRule> HTTP_RULE_TRAITS = new RuleSelector.Traits<>() {
        @Override
        public boolean enabled(HttpRule rule) {
            return !Boolean.FALSE.equals(rule.getEnabled());
        }

        @Override
        public boolean unconditional(HttpRule rule) {
            return isBlank(rule.getBodyCondition()) && isBlank(rule.getQueryCondition())
                    && isBlank(rule.getHeaderCondition());
        }

        private boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    };

    public List<HttpRule> findAllHttpRules() {
        return httpHandler.findAllRules();
    }

    public List<HttpRule> findHttpRulesByResponseId(Long responseId) {
        return httpRuleRepository.findByResponseId(responseId);
    }

    public Optional<HttpRule> findHttpRuleById(String id) {
        return httpHandler.findById(id);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.HTTP_RULES_CACHE, allEntries = true)
    public HttpRule saveHttpRule(HttpRule rule) {
        log.info("Saving HTTP rule and clearing cache: {}", rule.getMatchKey());
        if (rule.getId() != null) {
            httpHandler.findById(rule.getId()).ifPresent(existing -> rule.setCreatedBy(existing.getCreatedBy()));
        }
        rule.setUpdatedBy(CurrentOperator.username());
        HttpRule saved = (HttpRule) httpHandler.save(rule);
        cacheInvalidationService.ifPresent(s -> s.publishInvalidation(Protocol.HTTP));
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.HTTP_RULES_CACHE, allEntries = true)
    public void deleteHttpRuleById(String id) {
        log.info("Deleting HTTP rule and clearing cache: id={}", id);
        httpHandler.deleteById(id);
        cacheInvalidationService.ifPresent(s -> s.publishInvalidation(Protocol.HTTP));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.HTTP_RULES_CACHE, allEntries = true)
    public int deleteAllHttpRules() {
        int count = httpHandler.deleteAll();
        if (count > 0) {
            cacheInvalidationService.ifPresent(s -> s.publishInvalidation(Protocol.HTTP));
        }
        log.info("Deleted all {} HTTP rules", count);
        return count;
    }
}
