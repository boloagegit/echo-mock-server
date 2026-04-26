package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.protocol.http.HttpProtocolHandler;
import com.echo.repository.HttpRuleRepository;
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
        List<HttpRule> rules = proxy().findPreparedHttpRules(host, path, method);
        return findMatchingHttpRuleInternal(rules, body, queryString, headers);
    }

    @Cacheable(cacheNames = CacheConfig.HTTP_RULES_CACHE,
               key = "'http-prepared:' + #host + ':' + #path + ':' + #method")
    public List<HttpRule> findPreparedHttpRules(String host, String path, String method) {
        log.debug("Cache miss - preparing HTTP rules: host={}, path={}, method={}", host, path, method);
        List<HttpRule> rules = httpHandler.findWithFallback(host, path, method);
        return prepareHttpRules(rules);
    }

    private List<HttpRule> prepareHttpRules(List<HttpRule> rules) {
        if (rules.isEmpty()) {
            return rules;
        }
        Set<Long> validResponseIds = ruleService.getValidResponseIds(rules.stream()
                .map(HttpRule::getResponseId).filter(id -> id != null).collect(Collectors.toSet()));
        return rules.stream()
                .filter(r -> r.getResponseId() == null || validResponseIds.contains(r.getResponseId()))
                .sorted(Comparator
                        .comparing((HttpRule r) -> "*".equals(r.getMatchKey()) ? 1 : 0)
                        .thenComparing(Comparator.<HttpRule, Integer>comparing(
                                r -> r.getPriority() != null ? r.getPriority() : Integer.MIN_VALUE).reversed())
                        .thenComparing(r -> r.getTargetHost() == null || r.getTargetHost().isEmpty() ? 1 : 0)
                        .thenComparing(HttpRule::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private MatchResult<HttpRule> findMatchingHttpRuleInternal(List<HttpRule> rules, String body,
            String queryString, Map<String, String> headers) {
        HttpRule matched = null;
        HttpRule fallbackRule = null;
        ConditionMatcher.PreparedBody prepared = conditionMatcher.prepareBody(body);

        for (HttpRule rule : rules) {
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                continue;
            }

            boolean hasCondition = (rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank())
                    || (rule.getQueryCondition() != null && !rule.getQueryCondition().isBlank())
                    || (rule.getHeaderCondition() != null && !rule.getHeaderCondition().isBlank());

            if (hasCondition) {
                if (conditionMatcher.matchesPrepared(
                        rule.getBodyCondition(), rule.getQueryCondition(),
                        rule.getHeaderCondition(), prepared, queryString, headers)) {
                    matched = rule;
                    break;
                }
            } else {
                if (fallbackRule == null) {
                    fallbackRule = rule;
                }
            }
        }

        if (matched == null && fallbackRule != null) {
            matched = fallbackRule;
        }

        List<MatchChainEntry> chain = matched != null
                ? List.of(MatchChainEntry.fromHttp(matched, "match"))
                : List.of();
        return new MatchResult<>(matched, chain);
    }

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
