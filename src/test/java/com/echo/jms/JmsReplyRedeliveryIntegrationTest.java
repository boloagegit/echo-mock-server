package com.echo.jms;

import com.echo.config.JmsConfig;
import com.echo.config.JmsProperties;
import com.echo.pipeline.JmsMockPipeline;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import jakarta.jms.*;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.SessionAwareMessageListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real TCP Artemis and Spring local transactions; this is not an EMS acceptance test. */
class JmsReplyRedeliveryIntegrationTest {
    private EmbeddedActiveMQ broker;
    private ActiveMQConnectionFactory connectionFactory;
    private DefaultMessageListenerContainer container;
    private final AtomicReference<Session> sourceSession = new AtomicReference<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private final List<Boolean> redelivered = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final JmsMessageMemoryBudget budget = new JmsMessageMemoryBudget(1048576, 8);

    @BeforeEach
    void startBroker() throws Exception {
        var configuration = new ConfigurationImpl().setPersistenceEnabled(false)
                .setSecurityEnabled(false).setJMXManagementEnabled(false)
                .addAcceptorConfiguration("tcp", "tcp://127.0.0.1:0")
                .addAddressSetting("#", new AddressSettings().setRedeliveryDelay(50)
                        .setMaxDeliveryAttempts(2).setDeadLetterAddress(SimpleString.of("DLQ"))
                        .setAutoCreateQueues(false).setAutoCreateAddresses(false));
        broker = new EmbeddedActiveMQ().setConfiguration(configuration);
        broker.start();
        for (String name : List.of("SOURCE", "DLQ")) {
            broker.getActiveMQServer().createQueue(QueueConfiguration.of(name)
                    .setAddress(name).setAutoCreateAddress(true)
                    .setRoutingType(RoutingType.ANYCAST).setDurable(false));
        }
        int port = broker.getActiveMQServer().getRemotingService().getAcceptor("tcp").getActualPort();
        connectionFactory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port);
    }

    @AfterEach
    void stopBroker() throws Exception {
        if (container != null) container.shutdown();
        if (connectionFactory != null) connectionFactory.close();
        if (broker != null) broker.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transientFailureBeforeOrAfterSendRollsBackAndRedeliversOnlyOneReply(boolean afterSend) throws Exception {
        var sends = new AtomicInteger();
        var template = new JmsTemplate(connectionFactory) {
            @Override
            protected void doSend(Session session, Destination destination, MessageCreator creator) throws JMSException {
                assertThat(session).isSameAs(sourceSession.get());
                assertThat(session.getTransacted()).isTrue();
                int attempt = sends.incrementAndGet();
                if (attempt == 1 && !afterSend) throw new JMSException("injected before reply send");
                super.doSend(session, destination, creator);
                if (attempt == 1) throw new JMSException("injected after transactional reply send");
            }
        };
        startListener(template);
        try (Connection connection = connectionFactory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            connection.start();
            TemporaryQueue replyTo = session.createTemporaryQueue();
            try (MessageConsumer replies = session.createConsumer(replyTo)) {
                String id = send(session, replyTo);
                TextMessage reply = (TextMessage) replies.receive(10000);
                assertThat(reply).isNotNull();
                assertThat(reply.getText()).isEqualTo("reply");
                assertThat(reply.getJMSCorrelationID()).isEqualTo(id);
                assertThat(replies.receive(200)).isNull();
                assertThat(attempts).hasValue(2);
                assertThat(sends).hasValue(2);
                assertThat(redelivered).containsExactly(false, true);
                assertThat(failures).hasSize(1);
                assertThat(budget.reservedBytes()).isZero();
            } finally { replyTo.delete(); }
        }
    }

    @Test
    void deletedReplyDestinationUsesFiniteBrokerRedeliveryAndDeadLetterPolicy() throws Exception {
        startListener(new JmsTemplate(connectionFactory));
        try (Connection connection = connectionFactory.createConnection();
             Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageConsumer deadLetters = session.createConsumer(session.createQueue("DLQ"))) {
            connection.start();
            TemporaryQueue deletedReplyTo = session.createTemporaryQueue();
            deletedReplyTo.delete();
            String id = send(session, deletedReplyTo);
            Message failed = deadLetters.receive(10000);
            assertThat(failed).isNotNull();
            assertThat(failed.getJMSMessageID()).isEqualTo(id);
            assertThat(attempts).hasValue(2);
            assertThat(redelivered).containsExactly(false, true);
            assertThat(failures).hasSize(2);
            assertThat(budget.reservedBytes()).isZero();
        }
    }

    private void startListener(JmsTemplate template) {
        var manager = mock(JmsConnectionManager.class);
        when(manager.getJmsTemplate()).thenReturn(template);
        var pipeline = mock(JmsMockPipeline.class);
        when(pipeline.execute(any())).thenReturn(PipelineResult.builder().response(
                MockResponse.builder().status(200).body("reply").build()).build());
        var listener = new MockJmsListener(manager, new JmsProperties(), pipeline,
                new JmsEndpointExtractor(), budget);
        var endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("reply-rollback");
        endpoint.setDestination("SOURCE");
        endpoint.setMessageListener(listener::onMessage);
        container = new JmsConfig().jmsListenerContainerFactory(connectionFactory, new JmsProperties(), "1-1")
                .createListenerContainer(endpoint);
        container.setMessageListener((SessionAwareMessageListener<Message>) (message, session) -> {
            sourceSession.set(session);
            attempts.incrementAndGet();
            redelivered.add(message.getJMSRedelivered());
            listener.onMessage(message);
        });
        container.setErrorHandler(failures::add);
        container.initialize();
        container.start();
    }

    private String send(Session session, Destination replyTo) throws JMSException {
        try (MessageProducer producer = session.createProducer(session.createQueue("SOURCE"))) {
            TextMessage request = session.createTextMessage("request");
            request.setJMSReplyTo(replyTo);
            producer.send(request);
            return request.getJMSMessageID();
        }
    }
}
