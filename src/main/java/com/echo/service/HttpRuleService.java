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
 * HTTP Rules Service
 * <p>
 * Responsible for the CRUD operation and request matching logic of the HTTP rule.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HttpRuleService {

    private final HttpProtocolHandler httpHandler;
    private final RuleService ruleService;
    private final HttpRuleRepository httpRuleRepository;
    private final ConditionMatcher conditionMatcher;

    @org.springframework.beans.factory.annotation.Autowired
    private ResponseSnapshotReads responseSnapshotReads;

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
     * Filter out this path candidate from the common rule set of host + method.
     * Dynamic URL (such as /users/1, /users/2) no longer occupy one Caffeine entry each.
     */
    public List<HttpRule> findPreparedHttpRules(String host, String path, String method) {
        // The unit test/tool that directly constructs service does not have Spring proxy, and the original query path is maintained.
        if (applicationContext == null) {
            return prepareHttpRules(httpHandler.findWithFallback(host, path, method));
        }
        return proxy().httpRuleSnapshot().candidates(host, method, path);
    }

    /** Candidate rules for host + method (sorted, not filtered by path), taken from the current rule snapshot. */
    public List<HttpRule> findPreparedHttpRuleSet(String host, String method) {
        return proxy().httpRuleSnapshot().candidates(host, method);
    }

    /**
     * Current HTTP-rule snapshot ({@link RuleSnapshots}): rebuild in the background when rule or Response changes invalidate {@code httpRules};
     * Request to continue using old snapshot during rebuild. There is no unit test for Spring context built directly.
     */
    public HttpRuleSnapshot httpRuleSnapshot() {
        RuleSnapshots snapshots = applicationContext == null ? null
                : applicationContext.getBeanProvider(RuleSnapshots.class).getIfAvailable();
        return snapshots != null ? snapshots.http() : buildHttpRuleSnapshot();
    }

    /** Create HTTP rule snapshot from database (called by {@link RuleSnapshots} in the background). */
    HttpRuleSnapshot buildHttpRuleSnapshot() {
        if (responseSnapshotReads == null) {

        List<HttpRule> rules = httpRuleRepository.findByEnabledTrueOrderByIdAsc();
        Set<Long> validResponseIds = ruleService.getValidResponseIds(rules.stream()
                .map(HttpRule::getResponseId).filter(id -> id != null).collect(Collectors.toSet()));
        List<HttpRule> valid = rules.stream()
                .filter(r -> r.getResponseId() == null || validResponseIds.contains(r.getResponseId()))
                .toList();
        log.debug("Building HTTP rule snapshot: {} enabled rules", valid.size());
        return new HttpRuleSnapshot(valid, HTTP_RULE_ORDER, conditionMatcher);
        }
        return responseSnapshotReads.snapshot(this::buildFreshHttpRuleSnapshot);
    }

    private HttpRuleSnapshot buildFreshHttpRuleSnapshot() {
        List<HttpRule> rules = new java.util.ArrayList<>();
        Map<Long, ResponseRevision> revisions = new java.util.HashMap<>();
        for (Object[] row : httpRuleRepository.findSnapshotRows()) {
            HttpRule rule = (HttpRule) row[0];
            rules.add(rule);
            if (rule.getResponseId() != null)
                revisions.put(
                        rule.getResponseId(), new ResponseRevision((String) row[1], (Long) row[2]));
        }
        return new HttpRuleSnapshot(rules, HTTP_RULE_ORDER, conditionMatcher, revisions);
    }

    /** Only used in the caller's authoritative read transaction. */
    public List<HttpRule> freshCandidates(String host, String path, String method) {
        return buildFreshHttpRuleSnapshot().candidates(host, method, path);
    }

    /** Candidate order: exact paths before wildcards, descending priority, explicit host before unspecified host, then newest creation time. */
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
                rule -> rule.getMatchSpec() != null
                        ? conditionMatcher.matchesPrepared(rule.getMatchSpec(), null, null, null,
                                prepared, queryString, headers)
                        : conditionMatcher.matchesPrepared(rule.getBodyCondition(), rule.getQueryCondition(),
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
            return rule.getMatchSpec() == null && isBlank(rule.getBodyCondition())
                    && isBlank(rule.getQueryCondition()) && isBlank(rule.getHeaderCondition());
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
