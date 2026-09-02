package com.echo.config;

import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnectorFactory;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.server.config.impl.JMSConfigurationImpl;
import org.apache.activemq.artemis.jms.server.config.impl.JMSQueueConfigurationImpl;
import org.apache.activemq.artemis.jms.server.embedded.EmbeddedJMS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the delivery contract against a real embedded Artemis broker.
 * A polling listener must not acknowledge a message when its callback fails.
 */
class JmsListenerTransactionIntegrationTest {

    private static final String QUEUE_NAME = "jms.transaction.redelivery";
    private static final int SERVER_ID = 7_531;

    private EmbeddedJMS broker;
    private ActiveMQConnectionFactory connectionFactory;
    private DefaultMessageListenerContainer listenerContainer;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> transportParameters = Map.of("serverId", SERVER_ID);
        ConfigurationImpl configuration = new ConfigurationImpl()
                .setPersistenceEnabled(false)
                .setSecurityEnabled(false)
                .setJMXManagementEnabled(false)
                .addAcceptorConfiguration(new TransportConfiguration(
                        InVMAcceptorFactory.class.getName(), transportParameters, "test-in-vm"))
                .addAddressSetting("#", new AddressSettings()
                        .setRedeliveryDelay(25)
                        .setMaxRedeliveryDelay(100)
                        .setRedeliveryMultiplier(2.0)
                        .setMaxDeliveryAttempts(-1));
        JMSConfigurationImpl jmsConfiguration = new JMSConfigurationImpl()
                .setQueueConfigurations(List.of(new JMSQueueConfigurationImpl()
                        .setName(QUEUE_NAME)
                        .setBindings(QUEUE_NAME)
                        .setDurable(false)));

        broker = new EmbeddedJMS()
                .setConfiguration(configuration)
                .setJmsConfiguration(jmsConfiguration)
                .start();

        TransportConfiguration connector = new TransportConfiguration(
                InVMConnectorFactory.class.getName(), transportParameters, "test-in-vm");
        ServerLocator locator = ActiveMQClient.createServerLocatorWithoutHA(connector);
        connectionFactory = new ActiveMQConnectionFactory(locator);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (listenerContainer != null) {
            listenerContainer.stop();
        }
        if (connectionFactory != null) {
            connectionFactory.close();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    void failedListenerInvocationIsRedeliveredByEmbeddedArtemis() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean redelivered = new AtomicBoolean();
        CountDownLatch secondAttempt = new CountDownLatch(1);

        SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("transaction-redelivery");
        endpoint.setDestination(QUEUE_NAME);
        endpoint.setMessageListener(message -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new IllegalStateException("forced transient listener failure");
            }
            try {
                redelivered.set(message.getJMSRedelivered());
            } catch (Exception e) {
                throw new IllegalStateException("Could not inspect redelivery flag", e);
            } finally {
                secondAttempt.countDown();
            }
        });

        JmsProperties properties = new JmsProperties();
        DefaultJmsListenerContainerFactory factory = new JmsConfig()
                .jmsListenerContainerFactory(connectionFactory, properties, "1-1");
        listenerContainer = factory.createListenerContainer(endpoint);
        // A container created directly (outside Spring's bean lifecycle) must
        // be initialized before start schedules its polling invoker.
        listenerContainer.initialize();
        listenerContainer.start();
        waitForActiveConsumer();

        sendMessage();

        assertThat(secondAttempt.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(redelivered).isTrue();
    }

    private void waitForActiveConsumer() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (listenerContainer.getActiveConsumerCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(listenerContainer.getActiveConsumerCount()).isPositive();
    }

    private void sendMessage() throws Exception {
        try (Connection connection = connectionFactory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageProducer producer = session.createProducer(session.createQueue(QUEUE_NAME))) {
            TextMessage message = session.createTextMessage("transactional-payload");
            producer.send(message);
        }
    }
}
