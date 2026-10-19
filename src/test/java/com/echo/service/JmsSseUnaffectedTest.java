package com.echo.service;

import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.JmsRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.protocol.jms.JmsProtocolHandler;
import com.echo.repository.JmsRuleRepository;
import com.echo.repository.ResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Confirm that JMS is not affected by SSE functionality.
 * <p>
 * - JmsRule No sseEnabled field
 * - sseEnabled in RuleDto of JMS rule is null
 * - JMS rule matching is completely unaffected by the SSE function
 *
 * Validates: Requirements 7.2, 7.4
 */
@ExtendWith(MockitoExtension.class)
class JmsSseUnaffectedTest {

    @Mock
    private JmsRuleRepository jmsRuleRepository;
    @Mock
    private RuleService ruleService;
    @Mock
    private ConditionMatcher conditionMatcher;

    private JmsProtocolHandler jmsHandler;
    private JmsRuleService jmsRuleService;

    @BeforeEach
    void setUp() {
        jmsHandler = new JmsProtocolHandler(jmsRuleRepository, null);
        jmsRuleService = new JmsRuleService(Optional.of(jmsHandler), ruleService, jmsRuleRepository, conditionMatcher, Optional.empty(), Optional.empty(), null);
    }

    // --- JmsRule No sseEnabled field ---

    @Test
    void jmsRule_doesNotHaveSseEnabledField() {
        List<String> fieldNames = Arrays.stream(JmsRule.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertThat(fieldNames).doesNotContain("sseEnabled");
    }

    @Test
    void jmsRule_inheritedFields_doNotContainSseEnabled() {
        // Check all fields including inherited from BaseRule
        List<String> allFieldNames = new java.util.ArrayList<>();
        Class<?> clazz = JmsRule.class;
        while (clazz != null) {
            Arrays.stream(clazz.getDeclaredFields())
                    .map(Field::getName)
                    .forEach(allFieldNames::add);
            clazz = clazz.getSuperclass();
        }
        assertThat(allFieldNames).doesNotContain("sseEnabled");
    }

    // --- sseEnabled in RuleDto of JMS rule is null ---

    @Test
    void toDto_sseEnabled_isNull() {
        JmsRule rule = JmsRule.builder()
                .id("jms-1")
                .queueName("ORDER.QUEUE")
                .bodyCondition("type=ORDER")
                .priority(0)
                .enabled(true)
                .delayMs(0L)
                .responseId(100L)
                .createdAt(LocalDateTime.now())
                .build();

        RuleDto dto = jmsHandler.toDto(rule, null, false);

        assertThat(dto.getSseEnabled()).isNull();
        assertThat(dto.getProtocol()).isEqualTo(Protocol.JMS);
    }

    @Test
    void toDto_withResponse_sseEnabled_isNull() {
        JmsRule rule = JmsRule.builder()
                .id("jms-2")
                .queueName("PAYMENT.QUEUE")
                .priority(1)
                .enabled(true)
                .delayMs(500L)
                .responseId(200L)
                .createdAt(LocalDateTime.now())
                .build();
        Response response = new Response();
        response.setId(200L);
        response.setBody("{\"status\":\"ok\"}");

        RuleDto dto = jmsHandler.toDto(rule, response, true);

        assertThat(dto.getSseEnabled()).isNull();
        assertThat(dto.getResponseBody()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void fromDto_ignoresSseEnabled() {
        RuleDto dto = RuleDto.builder()
                .id("jms-3")
                .protocol(Protocol.JMS)
                .matchKey("TEST.QUEUE")
                .bodyCondition("type=TEST")
                .priority(0)
                .enabled(true)
                .delayMs(0L)
                .sseEnabled(true) // JMS should be ignored even if sseEnabled=true is set
                .build();

        BaseRule rule = jmsHandler.fromDto(dto);

        assertThat(rule).isInstanceOf(JmsRule.class);
        assertThat(rule.getProtocol()).isEqualTo(Protocol.JMS);
        // JmsRule does not have a sseEnabled field, so when it is converted back to DTO it should be null
        RuleDto roundTripped = jmsHandler.toDto(rule, null, false);
        assertThat(roundTripped.getSseEnabled()).isNull();
    }

    // --- JMS rule matching is not affected by SSE ---

    @Test
    void jmsRuleMatching_worksNormally() {
        JmsRule rule = JmsRule.builder()
                .id("jms-match-1")
                .queueName("ORDER.QUEUE")
                .bodyCondition("type=ORDER")
                .priority(0)
                .enabled(true)
                .delayMs(0L)
                .responseId(1L)
                .createdAt(LocalDateTime.now())
                .build();
        when(jmsRuleRepository.findByQueueNameOrWildcard("ORDER.QUEUE")).thenReturn(List.of(rule));
        when(ruleService.getValidResponseIds(any()))
                .thenReturn(Set.of(1L));
        when(conditionMatcher.matchesPrepared(any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        MatchResult<JmsRule> matchResult = jmsRuleService.findMatchingJmsRuleWithCandidates("ORDER.QUEUE", "{\"type\":\"ORDER\"}");

        assertThat(matchResult.isMatched()).isTrue();
        assertThat(matchResult.getMatchedRule().getId()).isEqualTo("jms-match-1");
        assertThat(matchResult.getMatchedRule().getProtocol()).isEqualTo(Protocol.JMS);
    }

    @Test
    void jmsRuleFallback_worksNormally() {
        JmsRule exactRule = JmsRule.builder()
                .id("exact")
                .queueName("ORDER.QUEUE")
                .priority(0)
                .enabled(true)
                .delayMs(0L)
                .createdAt(LocalDateTime.now())
                .build();
        JmsRule wildcardRule = JmsRule.builder()
                .id("wildcard")
                .queueName("*")
                .priority(0)
                .enabled(true)
                .delayMs(0L)
                .createdAt(LocalDateTime.now())
                .build();
        when(jmsRuleRepository.findByQueueNameOrWildcard("ORDER.QUEUE"))
                .thenReturn(List.of(exactRule, wildcardRule));

        MatchResult<JmsRule> matchResult = jmsRuleService.findMatchingJmsRuleWithCandidates("ORDER.QUEUE", "{}");

        assertThat(matchResult.isMatched()).isTrue();
        assertThat(matchResult.getMatchedRule().getId()).isEqualTo("exact");
    }

    @Test
    void jmsRuleMatchChain_worksNormally() {
        JmsRule condRule = JmsRule.builder()
                .id("cond")
                .queueName("ORDER.QUEUE")
                .bodyCondition("type=VIP")
                .priority(0)
                .enabled(true)
                .delayMs(0L)
                .responseId(1L)
                .createdAt(LocalDateTime.now())
                .build();
        JmsRule fallbackRule = JmsRule.builder()
                .id("fallback")
                .queueName("ORDER.QUEUE")
                .priority(0)
                .enabled(true)
                .delayMs(0L)
                .responseId(2L)
                .createdAt(LocalDateTime.now())
                .build();
        when(jmsRuleRepository.findByQueueNameOrWildcard("ORDER.QUEUE"))
                .thenReturn(List.of(condRule, fallbackRule));
        when(ruleService.getValidResponseIds(any()))
                .thenReturn(Set.of(1L, 2L));
        when(conditionMatcher.matchesPrepared(any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        MatchResult<JmsRule> matchResult =
                jmsRuleService.findMatchingJmsRuleWithCandidates("ORDER.QUEUE", "{\"type\":\"NORMAL\"}");

        assertThat(matchResult.isMatched()).isTrue();
        assertThat(matchResult.getMatchedRule().getId()).isEqualTo("fallback");
        assertThat(matchResult.getMatchChain()).hasSize(1);
    }

}
