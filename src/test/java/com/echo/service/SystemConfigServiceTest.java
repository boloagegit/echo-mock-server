package com.echo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SystemConfigServiceTest {

    private SystemConfigService service;

    @BeforeEach
    void setUp() {
        service = new SystemConfigService();
        ReflectionTestUtils.setField(service, "httpAlias", "REST");
        ReflectionTestUtils.setField(service, "jmsAlias", "MQ");
        ReflectionTestUtils.setField(service, "jmsEnabled", true);
        ReflectionTestUtils.setField(service, "ldapEnabled", false);
        ReflectionTestUtils.setField(service, "statsRetentionDays", 14);
        ReflectionTestUtils.setField(service, "auditRetentionDays", 30);
        ReflectionTestUtils.setField(service, "requestLogMaxRecords", 5000);
        ReflectionTestUtils.setField(service, "requestLogStore", "memory");
    }

    @Test
    void shouldReturnConfiguredValues() {
        assertThat(service.getHttpAlias()).isEqualTo("REST");
        assertThat(service.getHttpDisplayName()).isEqualTo("REST");
        assertThat(service.getJmsAlias()).isEqualTo("MQ");
        assertThat(service.getJmsDisplayName()).isEqualTo("MQ");
        assertThat(service.isJmsEnabled()).isTrue();
        assertThat(service.isLdapEnabled()).isFalse();
        assertThat(service.getStatsRetentionDays()).isEqualTo(14);
        assertThat(service.getAuditRetentionDays()).isEqualTo(30);
        assertThat(service.getRequestLogMaxRecords()).isEqualTo(5000);
        assertThat(service.getRequestLogStore()).isEqualTo("memory");
        assertThat(service.isRequestLogMemoryMode()).isTrue();
    }

    @Test
    void isRequestLogMemoryMode_shouldReturnFalseForDatabase() {
        ReflectionTestUtils.setField(service, "requestLogStore", "database");
        assertThat(service.isRequestLogMemoryMode()).isFalse();
    }
}
