package com.echo.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 系統設定服務 - 從 application.yml 讀取設定
 */
@Service
@Slf4j
@Getter
public class SystemConfigService {

    @Value("${echo.http.alias:HTTP}")
    private String httpAlias;

    @Value("${echo.jms.alias:JMS}")
    private String jmsAlias;

    @Value("${echo.jms.enabled:false}")
    private boolean jmsEnabled;

    @Value("${echo.ldap.enabled:false}")
    private boolean ldapEnabled;

    @Value("${echo.stats.retention-days:7}")
    private int statsRetentionDays;

    @Value("${echo.audit.retention-days:30}")
    private int auditRetentionDays;

    @Value("${echo.request-log.max-records:10000}")
    private int requestLogMaxRecords;

    @Value("${echo.request-log.store:database}")
    private String requestLogStore;

    @Value("${echo.request-log.include-body:true}")
    private boolean requestLogIncludeBody;

    @Value("${echo.request-log.max-body-size:65536}")
    private int requestLogMaxBodySize;

    public boolean isRequestLogMemoryMode() {
        return "memory".equalsIgnoreCase(requestLogStore);
    }

    public int getAuditRetentionDays() {
        return auditRetentionDays;
    }

    public String getHttpDisplayName() {
        return httpAlias;
    }

    public String getJmsDisplayName() {
        return jmsAlias;
    }
}
