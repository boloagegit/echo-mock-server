package com.echo.jms;

import com.echo.config.JmsProperties;
import jakarta.jms.ConnectionFactory;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * JMS 連線管理器
 */
@Component
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@Slf4j
public class JmsConnectionManager {

    private final JmsProperties jmsProperties;
    private final ConnectionFactory connectionFactory;

    @Getter
    private JmsTemplate jmsTemplate;
    private boolean connected = false;

    @Autowired
    public JmsConnectionManager(JmsProperties jmsProperties,
                                @Autowired(required = false) ConnectionFactory connectionFactory) {
        this.jmsProperties = jmsProperties;
        this.connectionFactory = connectionFactory;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        if (connectionFactory == null) {
            log.error("ConnectionFactory not available");
            return;
        }
        jmsTemplate = new JmsTemplate(connectionFactory);
        connected = true;
        log.info("JMS ConnectionManager initialized with embedded Artemis on port {}", jmsProperties.getPort());
    }

    public boolean isConnected() {
        return connected && jmsTemplate != null;
    }

    public JmsStatus getStatus() {
        return JmsStatus.builder()
                .enabled(jmsProperties.isEnabled())
                .connected(isConnected())
                .brokerUrl("tcp://localhost:" + jmsProperties.getPort())
                .queue(jmsProperties.getQueue())
                .displayName(jmsProperties.getAlias())
                .targetEnabled(jmsProperties.getTarget().isEnabled())
                .targetType(jmsProperties.getTarget().getType())
                .targetUrl(jmsProperties.getTarget().getServerUrl())
                .targetQueue(jmsProperties.getTarget().getQueue())
                .build();
    }

    @Builder
    @Getter
    public static class JmsStatus {
        private boolean enabled;
        private boolean connected;
        private String brokerUrl;
        private String queue;
        private String displayName;
        private boolean targetEnabled;
        private String targetType;
        private String targetUrl;
        private String targetQueue;
    }
}
