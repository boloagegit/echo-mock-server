package com.echo.config;

import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JmsConfigTest {

    @Test
    void jmsListenerContainerFactory_shouldCreateFactory() {
        JmsConfig config = new JmsConfig();
        ConnectionFactory factory = mock(ConnectionFactory.class);

        DefaultJmsListenerContainerFactory listenerFactory = config.jmsListenerContainerFactory(factory, "1-5");

        assertThat(listenerFactory).isNotNull();
    }
}
