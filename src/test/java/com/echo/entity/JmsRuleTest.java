package com.echo.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JmsRuleTest {

    @Test
    void shouldReturnJmsProtocol() {
        JmsRule rule = JmsRule.builder().queueName("TEST.QUEUE").build();
        assertThat(rule.getProtocol()).isEqualTo(Protocol.JMS);
    }

    @Test
    void shouldReturnCondition() {
        JmsRule rule = JmsRule.builder().queueName("TEST.QUEUE").bodyCondition("type=ORDER").build();
        assertThat(rule.getCondition()).isEqualTo("type=ORDER");
    }

    @Test
    void shouldReturnMatchKey_asQueueName() {
        JmsRule rule = JmsRule.builder().queueName("ORDER.QUEUE").build();
        assertThat(rule.getMatchKey()).isEqualTo("ORDER.QUEUE");
    }

    @Test
    void shouldReturnNullCondition_whenEmpty() {
        JmsRule rule = JmsRule.builder().queueName("TEST.QUEUE").build();
        assertThat(rule.getCondition()).isNull();
    }
}
