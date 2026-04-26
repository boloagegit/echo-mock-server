package com.echo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JMS 配置屬性
 */
@Component
@ConfigurationProperties(prefix = "echo.jms")
@Getter
@Setter
public class JmsProperties {

    private boolean enabled = false;
    private String alias = "JMS";
    private int port = 61616;
    private String username = "admin";
    private String password = "admin";

    /** Echo Embedded Artemis 上監聽的 Queue（外部服務連入的入口） */
    private String queue = "ECHO.REQUEST";

    /** 從 JMS 訊息 body 提取端點識別欄位（XML: element name, JSON: key name） */
    private String endpointField = "ServiceName";

    private Target target = new Target();

    @Getter
    @Setter
    public static class Target {
        private boolean enabled = false;
        private String type = "tibco";  // artemis | tibco
        private String serverUrl;
        private String username;
        private String password;
        private int timeoutSeconds = 30;

        /** 目標 JMS Server 上要轉發到的 Queue */
        private String queue = "TARGET.REQUEST";
    }
}
