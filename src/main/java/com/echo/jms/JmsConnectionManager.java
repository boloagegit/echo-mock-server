package com.echo.jms;

import com.echo.config.JmsProperties;
import jakarta.jms.ConnectionFactory;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * JMS Connection Manager
 */
@Component
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@Slf4j
public class JmsConnectionManager {

    private final JmsProperties jmsProperties;
    private final ConnectionFactory connectionFactory;
    private final ObjectProvider<JmsListenerEndpointRegistry> listenerRegistry;

    @Getter
    private JmsTemplate jmsTemplate;
    private boolean connected = false;

    public JmsConnectionManager(JmsProperties jmsProperties, ConnectionFactory connectionFactory) {
        this(jmsProperties, connectionFactory, null);
    }

    @Autowired
    public JmsConnectionManager(JmsProperties jmsProperties,
                                @Autowired(required = false) ConnectionFactory connectionFactory,
                                @Autowired(required = false) ObjectProvider<JmsListenerEndpointRegistry> listenerRegistry) {
        this.jmsProperties = jmsProperties;
        this.connectionFactory = connectionFactory;
        this.listenerRegistry = listenerRegistry;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        if (connectionFactory == null) {
            log.error("ConnectionFactory not available");
            return;
        }
        jmsTemplate = new JmsTemplate(connectionFactory);
        connected = true;
        if (jmsProperties.getSource().isExternal()) {
            log.info("JMS ConnectionManager initialized with external {} broker {}",
                    jmsProperties.getSource().getType(), jmsProperties.getSource().getServerUrl());
        } else {
            log.info("JMS ConnectionManager initialized with embedded Artemis on port {}", jmsProperties.getPort());
        }
    }

    public boolean isConnected() {
        return connected && jmsTemplate != null;
    }

    /** Receiving source: embedded or external broker type. */
    public String sourceType() {
        JmsProperties.Source source = jmsProperties.getSource();
        return source.isExternal() ? source.getType().trim().toLowerCase(java.util.Locale.ROOT)
                : JmsProperties.Source.EMBEDDED;
    }

    /** The broker address of the receiving source. */
    public String sourceUrl() {
        JmsProperties.Source source = jmsProperties.getSource();
        return source.isExternal() ? source.getServerUrl() : "tcp://localhost:" + jmsProperties.getPort();
    }

    /** Listener reception status. */
    public enum ListenerState { RECEIVING, RECONNECTING, UNKNOWN }

    /**
     * The listener is receiving when its container is running, is not reconnecting, and has at least one consumer.
     * {@link ListenerState#UNKNOWN} when there is no listener container information.
     */
    public ListenerState listenerState() {
        JmsListenerEndpointRegistry registry = listenerRegistry == null ? null : listenerRegistry.getIfAvailable();
        if (registry == null || registry.getListenerContainers().isEmpty()) {
            return ListenerState.UNKNOWN;
        }
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (!container.isRunning()) {
                return ListenerState.RECONNECTING;
            }
            if (container instanceof DefaultMessageListenerContainer dmlc
                    && (dmlc.isRecovering() || dmlc.getActiveConsumerCount() == 0)) {
                return ListenerState.RECONNECTING;
            }
        }
        return ListenerState.RECEIVING;
    }

    public JmsStatus getStatus() {
        return JmsStatus.builder()
                .enabled(jmsProperties.isEnabled())
                .connected(isConnected())
                .sourceType(sourceType())
                .listenerState(listenerState())
                .brokerUrl(sourceUrl())
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
        private String sourceType;
        private ListenerState listenerState;
        private String brokerUrl;
        private String queue;
        private String displayName;
        private boolean targetEnabled;
        private String targetType;
        private String targetUrl;
        private String targetQueue;
    }
}
