package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.config.JmsProperties;
import com.echo.core.engine.RuleSelector;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.echo.protocol.jms.JmsProtocolHandler;
import com.echo.repository.JmsRuleRepository;
import com.echo.util.CurrentOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JMS 規則服務
 * <p>
 * 負責 JMS 規則的 CRUD 操作與訊息匹配邏輯。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JmsRuleService {

    private final Optional<JmsProtocolHandler> jmsHandler;
    private final RuleService ruleService;
    private final JmsRuleRepository jmsRuleRepository;
    private final ConditionMatcher conditionMatcher;
    private final Optional<CacheInvalidationService> cacheInvalidationService;
    private final Optional<JmsProperties> jmsProperties;
    private final ApplicationContext applicationContext;

    private JmsRuleService proxy() {
        if (applicationContext == null) {
            return this;
        }
        return applicationContext.getBean(JmsRuleService.class);
    }

    private boolean isJmsEnabled() {
        return jmsHandler.isPresent();
    }

    private JmsProtocolHandler requireJmsHandler() {
        return jmsHandler.orElseThrow(() -> new IllegalStateException("JMS is not enabled"));
    }

    @Cacheable(cacheNames = CacheConfig.JMS_RULES_CACHE, key = "'jms:' + #queueName")
    public List<JmsRule> findJmsRules(String queueName) {
        if (!isJmsEnabled()) {
            return Collections.emptyList();
        }
        log.debug("Cache miss - querying JMS rules: queue={}", queueName);
        return requireJmsHandler().findWithFallback(queueName);
    }

    @Cacheable(cacheNames = CacheConfig.JMS_RULES_CACHE, key = "'jms:all'")
    public List<JmsRule> findAllJmsRulesCached() {
        if (!isJmsEnabled()) {
            return Collections.emptyList();
        }
        log.debug("Cache miss - querying all JMS rules");
        return requireJmsHandler().findAllRules();
    }

    /** 該 queue 的候選規則（已排序），取自目前的規則快照。 */
    public List<JmsRule> findPreparedJmsRules(String queueName) {
        if (!isJmsEnabled()) {
            return Collections.emptyList();
        }
        return proxy().jmsQueueSnapshot(queueName).rules();
    }

    /** 依 endpoint 值分桶後的候選規則，取自目前的規則快照；結果與原本逐一比對條件字串相同。 */
    public List<JmsRule> findBucketedJmsRules(String queueName, String endpointValue) {
        if (!isJmsEnabled()) {
            return Collections.emptyList();
        }
        return proxy().jmsQueueSnapshot(queueName).bucket(endpointValue);
    }

    /**
     * 某一 queue 的 JMS 規則快照（{@link RuleSnapshots}；queue 名稱來自設定檔，數量有限）：
     * 規則或 Response 異動清除 {@code jmsRules} 快取時在背景重建，重建期間訊息繼續使用舊快照。
     */
    public JmsQueueSnapshot jmsQueueSnapshot(String queueName) {
        RuleSnapshots snapshots = applicationContext == null ? null
                : applicationContext.getBeanProvider(RuleSnapshots.class).getIfAvailable();
        return snapshots != null ? snapshots.jms(queueName) : buildJmsQueueSnapshot(queueName);
    }

    /** 從資料庫建立某一 queue 的 JMS 規則快照（由 {@link RuleSnapshots} 在背景呼叫）。 */
    JmsQueueSnapshot buildJmsQueueSnapshot(String queueName) {
        log.debug("Building JMS rule snapshot: queue={}", queueName);
        List<JmsRule> rules = isJmsEnabled() ? requireJmsHandler().findWithFallback(queueName) : List.of();
        String endpointField = jmsProperties.map(JmsProperties::getEndpointField).orElse(null);
        return new JmsQueueSnapshot(prepareJmsRules(rules), endpointField, conditionMatcher);
    }

    private List<JmsRule> prepareJmsRules(List<JmsRule> rules) {
        return withValidResponses(rules).stream().sorted(JMS_RULE_ORDER).toList();
    }

    /** 拿掉回應已被刪除的規則：比對時不會考慮它們（判斷順序也必須一致）。 */
    public List<JmsRule> withValidResponses(List<JmsRule> rules) {
        if (rules.isEmpty()) {
            return rules;
        }
        Set<Long> validResponseIds = ruleService.getValidResponseIds(rules.stream()
                .map(JmsRule::getResponseId).filter(id -> id != null).collect(Collectors.toSet()));
        return rules.stream()
                .filter(r -> r.getResponseId() == null || validResponseIds.contains(r.getResponseId()))
                .toList();
    }

    /** 候選規則優先順序：非萬用 queue → priority 高 → 建立時間新。 */
    static final Comparator<JmsRule> JMS_RULE_ORDER = Comparator
            .comparing((JmsRule r) -> "*".equals(r.getQueueName()) ? 1 : 0)
            .thenComparing(Comparator.<JmsRule, Integer>comparing(
                    r -> r.getPriority() != null ? r.getPriority() : Integer.MIN_VALUE).reversed())
            .thenComparing(JmsRule::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    public MatchResult<JmsRule> findMatchingJmsRuleWithCandidates(String queueName, String body, String endpointValue) {
        return findMatchingJmsRuleWithCandidates(queueName, body, endpointValue, null);
    }

    /**
     * 帶入已解析的 PreparedBody，避免重複 parse XML/JSON
     */
    public MatchResult<JmsRule> findMatchingJmsRuleWithCandidates(String queueName, String body,
                                                                   String endpointValue,
                                                                   ConditionMatcher.PreparedBody preparedBody) {
        List<JmsRule> rules = (endpointValue != null && !endpointValue.isBlank())
                ? proxy().findBucketedJmsRules(queueName, endpointValue)
                : proxy().findPreparedJmsRules(queueName);

        return matchJmsRules(rules, body, preparedBody);
    }

    public MatchResult<JmsRule> findMatchingJmsRuleWithCandidates(String queueName, String body) {
        return findMatchingJmsRuleWithCandidates(queueName, body, null, null);
    }

    private MatchResult<JmsRule> matchJmsRules(List<JmsRule> rules, String body,
                                                ConditionMatcher.PreparedBody preparedBody) {
        ConditionMatcher.PreparedBody prepared = (preparedBody != null)
                ? preparedBody : conditionMatcher.prepareBody(body);
        JmsRule matched = RuleSelector.select(rules, RuleSelector.FallbackPolicy.EXACT_BEFORE_WILDCARD,
                JMS_RULE_TRAITS, rule -> true,
                rule -> rule.getMatchSpec() != null
                        ? conditionMatcher.matchesPrepared(rule.getMatchSpec(), null, null, null, prepared, null, null)
                        : conditionMatcher.matchesPrepared(rule.getBodyCondition(), null, null, prepared, null, null))
                .selected();

        List<MatchChainEntry> chain = matched != null
                ? List.of(MatchChainEntry.fromJms(matched, "match"))
                : List.of();
        return new MatchResult<>(matched, chain);
    }

    private static final RuleSelector.Traits<JmsRule> JMS_RULE_TRAITS = new RuleSelector.Traits<>() {
        @Override
        public boolean enabled(JmsRule rule) {
            return !Boolean.FALSE.equals(rule.getEnabled());
        }

        @Override
        public boolean unconditional(JmsRule rule) {
            return rule.getMatchSpec() == null
                    && (rule.getBodyCondition() == null || rule.getBodyCondition().isBlank());
        }

        @Override
        public boolean wildcard(JmsRule rule) {
            return "*".equals(rule.getQueueName());
        }
    };

    public List<JmsRule> findAllJmsRules() {
        if (!isJmsEnabled()) {
            return Collections.emptyList();
        }
        return requireJmsHandler().findAllRules();
    }

    public List<JmsRule> findJmsRulesByResponseId(Long responseId) {
        return jmsRuleRepository.findByResponseId(responseId);
    }

    public Optional<JmsRule> findJmsRuleById(String id) {
        if (!isJmsEnabled()) {
            return Optional.empty();
        }
        return requireJmsHandler().findById(id);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.JMS_RULES_CACHE, allEntries = true)
    public JmsRule saveJmsRule(JmsRule rule) {
        log.info("Saving JMS rule and clearing cache: {}", rule.getQueueName());
        if (rule.getId() != null) {
            requireJmsHandler().findById(rule.getId())
                    .ifPresent(existing -> rule.setCreatedBy(existing.getCreatedBy()));
        }
        rule.setUpdatedBy(CurrentOperator.username());
        JmsRule saved = (JmsRule) requireJmsHandler().save(rule);
        cacheInvalidationService.ifPresent(s -> s.publishInvalidation(Protocol.JMS));
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.JMS_RULES_CACHE, allEntries = true)
    public void deleteJmsRuleById(String id) {
        log.info("Deleting JMS rule and clearing cache: id={}", id);
        requireJmsHandler().deleteById(id);
        cacheInvalidationService.ifPresent(s -> s.publishInvalidation(Protocol.JMS));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.JMS_RULES_CACHE, allEntries = true)
    public int deleteAllJmsRules() {
        int count = requireJmsHandler().deleteAll();
        if (count > 0) {
            cacheInvalidationService.ifPresent(s -> s.publishInvalidation(Protocol.JMS));
        }
        log.info("Deleted all {} JMS rules", count);
        return count;
    }
}
