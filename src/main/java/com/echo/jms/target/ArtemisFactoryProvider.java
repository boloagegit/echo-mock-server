package com.echo.jms.target;

import com.echo.config.JmsProperties;
import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ArtemisFactoryProvider implements JmsTargetFactoryProvider {

    @Override
    public boolean supports(String type) {
        return "artemis".equalsIgnoreCase(type);
    }

    @Override
    public ConnectionFactory create(JmsProperties.Target target) {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(target.getServerUrl());
        if (target.getUsername() != null && !target.getUsername().isBlank()) {
            factory.setUser(target.getUsername());
            factory.setPassword(target.getPassword());
        }
        log.info("Created Artemis ConnectionFactory for: {}", target.getServerUrl());
        return factory;
    }
}
