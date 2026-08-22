package com.echo.config;

import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.SingleConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JmsConfigTest {

    @Test
    void jmsListenerContainerFactory_shouldCreateFactory() {
        JmsConfig config = new JmsConfig();
        ConnectionFactory factory = mock(ConnectionFactory.class);

        DefaultJmsListenerContainerFactory listenerFactory = config.jmsListenerContainerFactory(
                factory, new JmsProperties(), "1-5");

        assertThat(listenerFactory).isNotNull();
    }

    @Test
    void jmsListenerContainerFactory_shouldLimitArtemisConsumerPrefetch() {
        JmsConfig config = new JmsConfig();
        ActiveMQConnectionFactory target = mock(ActiveMQConnectionFactory.class);
        SingleConnectionFactory wrapper = new SingleConnectionFactory(target);
        JmsProperties properties = new JmsProperties();
        properties.setConsumerWindowSize(32 * 1024);

        config.jmsListenerContainerFactory(wrapper, properties, "1-5");

        verify(target).setConsumerWindowSize(32 * 1024);
    }
}
