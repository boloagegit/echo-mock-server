package com.echo.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityTest {

    @Test
    void cacheEvent_shouldSetTimestamp() {
        CacheEvent event = new CacheEvent("TEST");
        assertThat(event.getEventType()).isEqualTo("TEST");
        assertThat(event.getTimestamp()).isNotNull();
    }

    @Test
    void ruleAuditLog_actionEnum_shouldHaveThreeValues() {
        assertThat(RuleAuditLog.Action.values()).containsExactly(
                RuleAuditLog.Action.CREATE,
                RuleAuditLog.Action.UPDATE,
                RuleAuditLog.Action.DELETE
        );
    }

    @Test
    void requestLog_onCreate_shouldSetRequestTime() {
        RequestLog log = new RequestLog();
        log.onCreate();
        assertThat(log.getRequestTime()).isNotNull();
    }

    @Test
    void protocol_shouldHaveTwoValues() {
        assertThat(Protocol.values()).containsExactly(Protocol.HTTP, Protocol.JMS);
    }
}
