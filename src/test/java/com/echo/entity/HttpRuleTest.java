package com.echo.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRuleTest {

    @Test
    void shouldReturnHttpProtocol() {
        HttpRule rule = HttpRule.builder().matchKey("/test").build();
        assertThat(rule.getProtocol()).isEqualTo(Protocol.HTTP);
    }

    @Test
    void shouldBuildCondition_bodyOnly() {
        HttpRule rule = HttpRule.builder().matchKey("/test").bodyCondition("type=VIP").build();
        assertThat(rule.getCondition()).isEqualTo("type=VIP");
    }

    @Test
    void shouldBuildCondition_queryOnly() {
        HttpRule rule = HttpRule.builder().matchKey("/test").queryCondition("id=123").build();
        assertThat(rule.getCondition()).isEqualTo("?id=123");
    }

    @Test
    void shouldBuildCondition_bodyAndQuery() {
        HttpRule rule = HttpRule.builder()
                .matchKey("/test").bodyCondition("type=VIP").queryCondition("id=123").build();
        assertThat(rule.getCondition()).isEqualTo("type=VIP;?id=123");
    }

    @Test
    void shouldReturnNullCondition_whenEmpty() {
        HttpRule rule = HttpRule.builder().matchKey("/test").build();
        assertThat(rule.getCondition()).isNull();
    }

    @Test
    void shouldDefaultSseEnabledToFalse() {
        HttpRule rule = HttpRule.builder().matchKey("/test").build();
        assertThat(rule.getSseEnabled()).isFalse();
    }

    @Test
    void shouldSetSseEnabledToTrue() {
        HttpRule rule = HttpRule.builder().matchKey("/test").sseEnabled(true).build();
        assertThat(rule.getSseEnabled()).isTrue();
    }

    @Test
    void sseEnabled_shouldNotAffectGetCondition() {
        HttpRule ruleWithSse = HttpRule.builder()
                .matchKey("/test").bodyCondition("type=VIP").sseEnabled(true).build();
        HttpRule ruleWithoutSse = HttpRule.builder()
                .matchKey("/test").bodyCondition("type=VIP").sseEnabled(false).build();
        assertThat(ruleWithSse.getCondition()).isEqualTo(ruleWithoutSse.getCondition());
    }

    @Test
    void sseEnabled_shouldNotAffectGetProtocol() {
        HttpRule rule = HttpRule.builder().matchKey("/test").sseEnabled(true).build();
        assertThat(rule.getProtocol()).isEqualTo(Protocol.HTTP);
    }
}
