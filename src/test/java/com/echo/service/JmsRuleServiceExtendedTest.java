package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.echo.protocol.jms.JmsProtocolHandler;
import com.echo.repository.JmsRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JmsRuleServiceExtendedTest {

    @Mock private JmsProtocolHandler jmsHandler;
    @Mock private RuleService ruleService;
    @Mock private JmsRuleRepository jmsRuleRepository;
    @Mock private ConditionMatcher conditionMatcher;
    @Mock private CacheInvalidationService cacheInvalidationService;

    private JmsRuleService jmsRuleService;
    private JmsProperties jmsProperties;

    @BeforeEach
    void setUp() {
        jmsProperties = new JmsProperties();
        jmsProperties.setEndpointField("ServiceName");
        jmsRuleService = new JmsRuleService(
                Optional.of(jmsHandler), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.of(cacheInvalidationService),
                Optional.of(jmsProperties), null);
    }

    @Test
    void findJmsRules_shouldReturnEmpty_whenJmsDisabled() {
        JmsRuleService disabledService = new JmsRuleService(
                Optional.empty(), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.empty(), Optional.empty(), null);

        assertThat(disabledService.findJmsRules("QUEUE")).isEmpty();
    }

    @Test
    void findAllJmsRulesCached_shouldReturnEmpty_whenJmsDisabled() {
        JmsRuleService disabledService = new JmsRuleService(
                Optional.empty(), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.empty(), Optional.empty(), null);

        assertThat(disabledService.findAllJmsRulesCached()).isEmpty();
    }

    @Test
    void findPreparedJmsRules_shouldReturnEmpty_whenJmsDisabled() {
        JmsRuleService disabledService = new JmsRuleService(
                Optional.empty(), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.empty(), Optional.empty(), null);

        assertThat(disabledService.findPreparedJmsRules("QUEUE")).isEmpty();
    }

    @Test
    void findBucketedJmsRules_shouldReturnEmpty_whenJmsDisabled() {
        JmsRuleService disabledService = new JmsRuleService(
                Optional.empty(), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.empty(), Optional.empty(), null);

        assertThat(disabledService.findBucketedJmsRules("QUEUE", "value")).isEmpty();
    }

    @Test
    void findAllJmsRules_shouldReturnEmpty_whenJmsDisabled() {
        JmsRuleService disabledService = new JmsRuleService(
                Optional.empty(), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.empty(), Optional.empty(), null);

        assertThat(disabledService.findAllJmsRules()).isEmpty();
    }

    @Test
    void findJmsRuleById_shouldReturnEmpty_whenJmsDisabled() {
        JmsRuleService disabledService = new JmsRuleService(
                Optional.empty(), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.empty(), Optional.empty(), null);

        assertThat(disabledService.findJmsRuleById("id")).isEmpty();
    }

    @Test
    void saveJmsRule_shouldThrow_whenJmsDisabled() {
        JmsRuleService disabledService = new JmsRuleService(
                Optional.empty(), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.empty(), Optional.empty(), null);

        JmsRule rule = JmsRule.builder().queueName("Q").build();
        assertThatThrownBy(() -> disabledService.saveJmsRule(rule))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deleteJmsRuleById_shouldThrow_whenJmsDisabled() {
        JmsRuleService disabledService = new JmsRuleService(
                Optional.empty(), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.empty(), Optional.empty(), null);

        assertThatThrownBy(() -> disabledService.deleteJmsRuleById("id"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deleteAllJmsRules_shouldThrow_whenJmsDisabled() {
        JmsRuleService disabledService = new JmsRuleService(
                Optional.empty(), ruleService, jmsRuleRepository,
                conditionMatcher, Optional.empty(), Optional.empty(), null);

        assertThatThrownBy(() -> disabledService.deleteAllJmsRules())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void saveJmsRule_shouldPublishInvalidation() {
        JmsRule rule = JmsRule.builder().queueName("Q").build();
        when(jmsHandler.save(rule)).thenReturn(rule);

        jmsRuleService.saveJmsRule(rule);

        verify(cacheInvalidationService).publishInvalidation(Protocol.JMS);
    }

    @Test
    void deleteJmsRuleById_shouldPublishInvalidation() {
        jmsRuleService.deleteJmsRuleById("id-1");

        verify(jmsHandler).deleteById("id-1");
        verify(cacheInvalidationService).publishInvalidation(Protocol.JMS);
    }

    @Test
    void deleteAllJmsRules_shouldPublishInvalidation_whenDeleted() {
        when(jmsHandler.deleteAll()).thenReturn(5);

        int count = jmsRuleService.deleteAllJmsRules();

        assertThat(count).isEqualTo(5);
        verify(cacheInvalidationService).publishInvalidation(Protocol.JMS);
    }

    @Test
    void deleteAllJmsRules_shouldNotPublishInvalidation_whenNoneDeleted() {
        when(jmsHandler.deleteAll()).thenReturn(0);

        int count = jmsRuleService.deleteAllJmsRules();

        assertThat(count).isEqualTo(0);
        verify(cacheInvalidationService, never()).publishInvalidation(any(Protocol.class));
    }

    @Test
    void findJmsRulesByResponseId_shouldDelegateToRepository() {
        JmsRule rule = JmsRule.builder().id("r1").queueName("Q").responseId(100L).build();
        when(jmsRuleRepository.findByResponseId(100L)).thenReturn(List.of(rule));

        List<JmsRule> result = jmsRuleService.findJmsRulesByResponseId(100L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("r1");
    }

    @Test
    void matchJmsRules_shouldSkipDisabledRules() {
        JmsRule disabled = JmsRule.builder().id("d1").queueName("Q")
                .enabled(false).priority(0).createdAt(LocalDateTime.now()).build();
        JmsRule enabled = JmsRule.builder().id("e1").queueName("Q")
                .enabled(true).priority(0).createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(disabled, enabled));

        MatchResult<JmsRule> result = jmsRuleService.findMatchingJmsRuleWithCandidates("Q", "{}", null);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchedRule().getId()).isEqualTo("e1");
    }

    @Test
    void matchJmsRules_shouldPreferExactQueueOverWildcard() {
        JmsRule exact = JmsRule.builder().id("exact").queueName("ORDER.Q")
                .enabled(true).priority(0).createdAt(LocalDateTime.now()).build();
        JmsRule wildcard = JmsRule.builder().id("wild").queueName("*")
                .enabled(true).priority(0).createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("ORDER.Q")).thenReturn(List.of(exact, wildcard));

        MatchResult<JmsRule> result = jmsRuleService.findMatchingJmsRuleWithCandidates("ORDER.Q", "{}", null);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchedRule().getId()).isEqualTo("exact");
    }

    @Test
    void findBucketedJmsRules_shouldReturnAll_whenEndpointValueNull() {
        JmsRule rule = JmsRule.builder().id("r1").queueName("Q")
                .enabled(true).priority(0).responseId(1L)
                .createdAt(LocalDateTime.now()).build();
        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(rule));
        when(ruleService.getValidResponseIds(any())).thenReturn(Set.of(1L));

        List<JmsRule> result = jmsRuleService.findBucketedJmsRules("Q", null);

        assertThat(result).hasSize(1);
    }

    @Test
    void findBucketedJmsRules_shouldFilterByEndpointValue() {
        JmsRule matchingRule = JmsRule.builder().id("r1").queueName("Q")
                .bodyCondition("ServiceName=OrderService")
                .enabled(true).priority(0).responseId(1L)
                .createdAt(LocalDateTime.now()).build();
        JmsRule nonMatchingRule = JmsRule.builder().id("r2").queueName("Q")
                .bodyCondition("ServiceName=PaymentService")
                .enabled(true).priority(1).responseId(2L)
                .createdAt(LocalDateTime.now()).build();
        JmsRule noConditionRule = JmsRule.builder().id("r3").queueName("Q")
                .enabled(true).priority(2).responseId(3L)
                .createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(matchingRule, nonMatchingRule, noConditionRule));
        when(ruleService.getValidResponseIds(any())).thenReturn(Set.of(1L, 2L, 3L));

        List<JmsRule> result = jmsRuleService.findBucketedJmsRules("Q", "OrderService");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(JmsRule::getId).containsExactly("r3", "r1");
    }

    @Test
    void findBucketedJmsRules_shouldReturnAll_whenEndpointFieldBlank() {
        jmsProperties.setEndpointField("");

        JmsRule rule = JmsRule.builder().id("r1").queueName("Q")
                .bodyCondition("ServiceName=OrderService")
                .enabled(true).priority(0).responseId(1L)
                .createdAt(LocalDateTime.now()).build();
        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(rule));
        when(ruleService.getValidResponseIds(any())).thenReturn(Set.of(1L));

        List<JmsRule> result = jmsRuleService.findBucketedJmsRules("Q", "OrderService");

        assertThat(result).hasSize(1);
    }

    @Test
    void bodyConditionMatchesEndpoint_shouldHandleOperators() {
        // *= operator: field=ServiceName, value=Order (exact match in bucketing)
        JmsRule containsRule = JmsRule.builder().id("r1").queueName("Q")
                .bodyCondition("ServiceName*=OrderService")
                .enabled(true).priority(0).responseId(1L)
                .createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(containsRule));
        when(ruleService.getValidResponseIds(any())).thenReturn(Set.of(1L));

        List<JmsRule> result = jmsRuleService.findBucketedJmsRules("Q", "OrderService");

        assertThat(result).hasSize(1);
    }

    @Test
    void bodyConditionMatchesEndpoint_shouldHandleXPathPrefix() {
        JmsRule xpathRule = JmsRule.builder().id("r1").queueName("Q")
                .bodyCondition("//ServiceName=OrderService")
                .enabled(true).priority(0).responseId(1L)
                .createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(xpathRule));
        when(ruleService.getValidResponseIds(any())).thenReturn(Set.of(1L));

        List<JmsRule> result = jmsRuleService.findBucketedJmsRules("Q", "OrderService");

        assertThat(result).hasSize(1);
    }

    @Test
    void bodyConditionMatchesEndpoint_shouldHandleMultipleConditions() {
        JmsRule multiRule = JmsRule.builder().id("r1").queueName("Q")
                .bodyCondition("ServiceName=OrderService;type=VIP")
                .enabled(true).priority(0).responseId(1L)
                .createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(multiRule));
        when(ruleService.getValidResponseIds(any())).thenReturn(Set.of(1L));

        List<JmsRule> result = jmsRuleService.findBucketedJmsRules("Q", "OrderService");

        assertThat(result).hasSize(1);
    }

    @Test
    void prepareJmsRules_shouldFilterInvalidResponseIds() {
        JmsRule validRule = JmsRule.builder().id("r1").queueName("Q")
                .enabled(true).priority(0).responseId(1L)
                .createdAt(LocalDateTime.now()).build();
        JmsRule invalidRule = JmsRule.builder().id("r2").queueName("Q")
                .enabled(true).priority(1).responseId(999L)
                .createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(validRule, invalidRule));
        when(ruleService.getValidResponseIds(any())).thenReturn(Set.of(1L));

        List<JmsRule> result = jmsRuleService.findPreparedJmsRules("Q");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("r1");
    }

    @Test
    void prepareJmsRules_shouldKeepRulesWithNullResponseId() {
        JmsRule noResponseRule = JmsRule.builder().id("r1").queueName("Q")
                .enabled(true).priority(0).responseId(null)
                .createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(noResponseRule));

        List<JmsRule> result = jmsRuleService.findPreparedJmsRules("Q");

        assertThat(result).hasSize(1);
    }

    @Test
    void matchJmsRules_shouldRespectPriority() {
        JmsRule lowPri = JmsRule.builder().id("low").queueName("Q")
                .enabled(true).priority(1).createdAt(LocalDateTime.now()).build();
        JmsRule highPri = JmsRule.builder().id("high").queueName("Q")
                .enabled(true).priority(10).createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(lowPri, highPri));

        MatchResult<JmsRule> result = jmsRuleService.findMatchingJmsRuleWithCandidates("Q", "{}", null);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchedRule().getId()).isEqualTo("high");
    }

    @Test
    void matchJmsRules_exactQueueCondition_winsOverWildcardCondition() {
        JmsRule wildcardCond = JmsRule.builder().id("wc-cond").queueName("*")
                .bodyCondition("//Type=ORDER").enabled(true).priority(0)
                .createdAt(LocalDateTime.now()).build();
        JmsRule exactCond = JmsRule.builder().id("exact-cond").queueName("Q")
                .bodyCondition("//Type=ORDER").enabled(true).priority(0)
                .createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(wildcardCond, exactCond));
        when(conditionMatcher.matchesPrepared(any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        MatchResult<JmsRule> result = jmsRuleService.findMatchingJmsRuleWithCandidates("Q", "<Msg><Type>ORDER</Type></Msg>", null);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchedRule().getId()).isEqualTo("exact-cond");
    }

    @Test
    void matchJmsRules_exactQueueNoCondition_winsOverWildcardNoCondition() {
        JmsRule wildcardNoCond = JmsRule.builder().id("wc-nc").queueName("*")
                .enabled(true).priority(0).createdAt(LocalDateTime.now()).build();
        JmsRule exactNoCond = JmsRule.builder().id("exact-nc").queueName("Q")
                .enabled(true).priority(0).createdAt(LocalDateTime.now()).build();

        when(jmsHandler.findWithFallback("Q")).thenReturn(List.of(wildcardNoCond, exactNoCond));

        MatchResult<JmsRule> result = jmsRuleService.findMatchingJmsRuleWithCandidates("Q", "{}", null);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchedRule().getId()).isEqualTo("exact-nc");
    }
}
