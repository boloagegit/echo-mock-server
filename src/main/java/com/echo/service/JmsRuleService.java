package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.config.JmsProperties;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.echo.protocol.jms.JmsProtocolHandler;
import com.echo.repository.JmsRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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

    @Cacheable(cacheNames = CacheConfig.JMS_RULES_CACHE, key = "'jms-prepared:' + #queueName")
    public List<JmsRule> findPreparedJmsRules(String queueName) {
        if (!isJmsEnabled()) {
            return Collections.emptyList();
        }
        log.debug("Cache miss - preparing JMS rules: queue={}", queueName);
        List<JmsRule> rules = requireJmsHandler().findWithFallback(queueName);
        return prepareJmsRules(rules);
    }

    @Cacheable(cacheNames = CacheConfig.JMS_RULES_CACHE,
               key = "'jms-bucketed:' + #queueName + ':' + (#endpointValue != null ? #endpointValue : '_all_')")
    public List<JmsRule> findBucketedJmsRules(String queueName, String endpointValue) {
        if (!isJmsEnabled()) {
            return Collections.emptyList();
        }
        List<JmsRule> allRules = proxy().findPreparedJmsRules(queueName);
        if (endpointValue == null || endpointValue.isBlank()) {
            return allRules;
        }

        String endpointField = jmsProperties.map(JmsProperties::getEndpointField).orElse(null);
        if (endpointField == null || endpointField.isBlank()) {
            return allRules;
        }

        log.debug("Cache miss - bucketing JMS rules: queue={}, endpointValue={}", queueName, endpointValue);

        List<JmsRule> bucketed = new ArrayList<>();
        for (JmsRule rule : allRules) {
            String cond = rule.getBodyCondition();
            if (cond == null || cond.isBlank()) {
                bucketed.add(rule);
            } else if (bodyConditionMatchesEndpoint(cond, endpointField, endpointValue)) {
                bucketed.add(rule);
            }
        }
        return List.copyOf(bucketed);
    }

    private boolean bodyConditionMatchesEndpoint(String bodyCondition, String endpointField, String endpointValue) {
        for (String part : bodyCondition.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = trimmed.startsWith("//") ? trimmed.substring(2) : trimmed;
            int eqIdx = normalized.indexOf('=');
            if (eqIdx <= 0) {
                continue;
            }
            char prev = normalized.charAt(eqIdx - 1);
            String field;
            if (prev == '!' || prev == '*' || prev == '~') {
                field = normalized.substring(0, eqIdx - 1).trim();
            } else {
                field = normalized.substring(0, eqIdx).trim();
            }
            String value = normalized.substring(eqIdx + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }

            if (field.equalsIgnoreCase(endpointField)) {
                return value.equals(endpointValue);
            }
        }
        return true;
    }

    private List<JmsRule> prepareJmsRules(List<JmsRule> rules) {
        if (rules.isEmpty()) {
            return rules;
        }
        Set<Long> validResponseIds = ruleService.getValidResponseIds(rules.stream()
                .map(JmsRule::getResponseId).filter(id -> id != null).collect(Collectors.toSet()));
        return rules.stream()
                .filter(r -> r.getResponseId() == null || validResponseIds.contains(r.getResponseId()))
                .sorted(Comparator
                        .comparing((JmsRule r) -> "*".equals(r.getQueueName()) ? 1 : 0)
                        .thenComparing(Comparator.<JmsRule, Integer>comparing(
                                r -> r.getPriority() != null ? r.getPriority() : Integer.MIN_VALUE).reversed())
                        .thenComparing(JmsRule::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

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
        JmsRule matched = null;
        JmsRule noConditionRule = null;
        JmsRule wildcardRule = null;
        ConditionMatcher.PreparedBody prepared = (preparedBody != null)
                ? preparedBody : conditionMatcher.prepareBody(body);

        for (JmsRule rule : rules) {
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                continue;
            }
            boolean isWildcard = "*".equals(rule.getQueueName());
            boolean hasCondition = rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank();

            if (hasCondition) {
                if (conditionMatcher.matchesPrepared(
                        rule.getBodyCondition(), null, null, prepared, null, null)) {
                    matched = rule;
                    break;
                }
            } else if (isWildcard) {
                if (wildcardRule == null) {
                    wildcardRule = rule;
                }
            } else {
                if (noConditionRule == null) {
                    noConditionRule = rule;
                }
            }
        }

        if (matched == null) {
            matched = noConditionRule != null ? noConditionRule : wildcardRule;
        }

        List<MatchChainEntry> chain = matched != null
                ? List.of(MatchChainEntry.fromJms(matched, "match"))
                : List.of();
        return new MatchResult<>(matched, chain);
    }

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
