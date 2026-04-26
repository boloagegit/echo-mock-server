package com.echo.service;

import com.echo.config.CacheConfig;
import com.echo.entity.HttpRule;
import com.echo.entity.JmsRule;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.ResponseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleServiceTest {

    @Mock
    private ProtocolHandlerRegistry protocolHandlerRegistry;

    @Mock
    private ResponseRepository responseRepository;

    @Mock
    private Cache<Long, String> responseBodyCache;

    @Mock
    private CacheConfig cacheConfig;

    @Mock
    private CacheInvalidationService cacheInvalidationService;

    @Mock
    private RuleAuditService ruleAuditService;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    void updateEnabled_shouldLogAudit_whenRuleExists() throws Exception {
        RuleService ruleService = new RuleService(
                protocolHandlerRegistry,
                responseRepository,
                responseBodyCache,
                cacheConfig,
                Optional.of(cacheInvalidationService),
                Optional.of(ruleAuditService),
                objectMapper
        );

        String ruleId = "test-rule-id";
        HttpRule rule = HttpRule.builder()
                .id(ruleId)
                .method("GET")
                .matchKey("/test")
                .enabled(true)
                .build();

        doReturn(List.of(rule)).when(protocolHandlerRegistry).findAllByIds(List.of(ruleId));
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"id\":\"test-rule-id\",\"enabled\":true}");
        when(protocolHandlerRegistry.updateEnabled(anyList(), anyBoolean())).thenReturn(1);

        ruleService.updateEnabled(List.of(ruleId), false);

        verify(ruleAuditService).logBatchUpdate(anyList(), anyList());
        verify(protocolHandlerRegistry).findAllByIds(List.of(ruleId));
        verify(protocolHandlerRegistry).updateEnabled(List.of(ruleId), false);
    }

    @Test
    void httpRule_isProtected_defaultFalse() {
        HttpRule rule = HttpRule.builder().matchKey("/api/test").method("GET").build();
        assertThat(rule.getIsProtected()).isFalse();
    }

    @Test
    void httpRule_isProtected_canBeSetTrue() {
        HttpRule rule = HttpRule.builder().matchKey("/api/test").method("GET").isProtected(true).build();
        assertThat(rule.getIsProtected()).isTrue();
    }

    @Test
    void jmsRule_isProtected_defaultFalse() {
        JmsRule rule = JmsRule.builder().queueName("TEST.QUEUE").build();
        assertThat(rule.getIsProtected()).isFalse();
    }

    @Test
    void jmsRule_isProtected_canBeSetTrue() {
        JmsRule rule = JmsRule.builder().queueName("TEST.QUEUE").isProtected(true).build();
        assertThat(rule.getIsProtected()).isTrue();
    }

    @Test
    void httpRule_setIsProtected_updatesValue() {
        HttpRule rule = HttpRule.builder().matchKey("/api/test").method("GET").isProtected(false).build();
        rule.setIsProtected(true);
        assertThat(rule.getIsProtected()).isTrue();
    }
}
