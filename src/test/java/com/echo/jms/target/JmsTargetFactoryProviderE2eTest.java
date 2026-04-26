package com.echo.jms.target;

import com.echo.config.JmsProperties;
import jakarta.jms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2E Integration Test — 策略模式 + Docker Artemis (port 61617)。
 * <p>
 * 前置條件：
 * <pre>
 * docker run -d --name artemis-test -p 61617:61616 -p 8162:8161 \
 *   -e ARTEMIS_USER=admin -e ARTEMIS_PASSWORD=admin \
 *   apache/activemq-artemis:latest
 * </pre>
 */
class JmsTargetFactoryProviderE2eTest {

    private static final String ARTEMIS_URL = "tcp://localhost:61617";
    private static final String ARTEMIS_USER = "admin";
    private static final String ARTEMIS_PASS = "admin";
    private static final String TEST_QUEUE = "E2E.TEST.QUEUE";

    private JmsProperties.Target target;

    static boolean isArtemisRunning() {
        try (Socket s = new Socket("localhost", 61617)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        target = new JmsProperties.Target();
        target.setServerUrl(ARTEMIS_URL);
        target.setUsername(ARTEMIS_USER);
        target.setPassword(ARTEMIS_PASS);
        target.setQueue(TEST_QUEUE);
        target.setTimeoutSeconds(5);
    }

    // ── ArtemisFactoryProvider ──

    @Test
    @EnabledIf("isArtemisRunning")
    void artemisProvider_shouldConnectAndSendReceive() throws Exception {
        ArtemisFactoryProvider provider = new ArtemisFactoryProvider();
        assertThat(provider.supports("artemis")).isTrue();
        assertThat(provider.supports("ARTEMIS")).isTrue();
        assertThat(provider.supports("tibco")).isFalse();

        ConnectionFactory factory = provider.create(target);
        assertThat(factory).isNotNull();

        try (Connection conn = factory.createConnection()) {
            conn.start();
            try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue queue = session.createQueue(TEST_QUEUE);

                // Send
                MessageProducer producer = session.createProducer(queue);
                String body = "<ServiceRequest><ServiceName>TestSvc</ServiceName><Data>hello</Data></ServiceRequest>";
                TextMessage sent = session.createTextMessage(body);
                producer.send(sent);

                // Receive
                MessageConsumer consumer = session.createConsumer(queue);
                Message received = consumer.receive(5000);

                assertThat(received).isInstanceOf(TextMessage.class);
                assertThat(((TextMessage) received).getText()).isEqualTo(body);
            }
        }
    }

    @Test
    @EnabledIf("isArtemisRunning")
    void artemisProvider_requestReplyPattern() throws Exception {
        ArtemisFactoryProvider provider = new ArtemisFactoryProvider();
        ConnectionFactory factory = provider.create(target);

        try (Connection conn = factory.createConnection()) {
            conn.start();

            // Simulate the same pattern as JmsTargetForwarder.forward()
            try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue destQueue = session.createQueue(TEST_QUEUE + ".REPLY");
                TemporaryQueue replyQueue = session.createTemporaryQueue();

                // Send with ReplyTo
                MessageProducer producer = session.createProducer(destQueue);
                TextMessage request = session.createTextMessage("<request>ping</request>");
                request.setJMSReplyTo(replyQueue);
                request.setJMSCorrelationID("corr-001");
                producer.send(request);

                // Simulate target server: consume from destQueue, reply to replyQueue
                MessageConsumer serverConsumer = session.createConsumer(destQueue);
                Message incoming = serverConsumer.receive(5000);
                assertThat(incoming).isNotNull();

                MessageProducer replier = session.createProducer(incoming.getJMSReplyTo());
                TextMessage response = session.createTextMessage("<response>pong</response>");
                response.setJMSCorrelationID(incoming.getJMSCorrelationID());
                replier.send(response);

                // Client receives reply
                MessageConsumer replyConsumer = session.createConsumer(replyQueue);
                Message reply = replyConsumer.receive(5000);

                assertThat(reply).isInstanceOf(TextMessage.class);
                assertThat(((TextMessage) reply).getText()).isEqualTo("<response>pong</response>");
                assertThat(reply.getJMSCorrelationID()).isEqualTo("corr-001");
            }
        }
    }

    // ── TibcoFactoryProvider ──

    static boolean isTibcoJarPresent() {
        try {
            Class.forName("com.tibco.tibjms.TibjmsConnectionFactory");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    @EnabledIf("isTibcoJarPresent")
    void tibcoProvider_shouldCreateFactory_whenJarPresent() throws Exception {
        TibcoFactoryProvider provider = new TibcoFactoryProvider();
        assertThat(provider.supports("tibco")).isTrue();
        assertThat(provider.supports("TIBCO")).isTrue();
        assertThat(provider.supports("artemis")).isFalse();

        target.setServerUrl("tcp://localhost:7222");
        ConnectionFactory factory = provider.create(target);
        assertThat(factory).isNotNull();
    }

    // ── IbmMqFactoryProvider ──

    @Test
    void ibmMqProvider_shouldThrowUnsupported() {
        IbmMqFactoryProvider provider = new IbmMqFactoryProvider();
        assertThat(provider.supports("ibm-mq")).isTrue();
        assertThat(provider.supports("IBM-MQ")).isTrue();
        assertThat(provider.supports("artemis")).isFalse();

        assertThatThrownBy(() -> provider.create(target))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("IBM MQ");
    }

    // ── Strategy dispatch ──

    @Test
    @EnabledIf("isArtemisRunning")
    void strategyDispatch_shouldSelectCorrectProvider() throws Exception {
        List<JmsTargetFactoryProvider> providers = List.of(
                new ArtemisFactoryProvider(),
                new TibcoFactoryProvider(),
                new IbmMqFactoryProvider());

        String type = "artemis";
        ConnectionFactory factory = providers.stream()
                .filter(p -> p.supports(type))
                .findFirst()
                .orElseThrow()
                .create(target);

        // Verify it actually works by creating a connection
        try (Connection conn = factory.createConnection()) {
            conn.start();
            try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue queue = session.createQueue("E2E.DISPATCH.TEST");
                MessageProducer producer = session.createProducer(queue);
                producer.send(session.createTextMessage("strategy dispatch ok"));

                MessageConsumer consumer = session.createConsumer(queue);
                Message msg = consumer.receive(3000);
                assertThat(msg).isInstanceOf(TextMessage.class);
                assertThat(((TextMessage) msg).getText()).isEqualTo("strategy dispatch ok");
            }
        }
    }

    @Test
    void strategyDispatch_shouldThrowForUnknownType() {
        List<JmsTargetFactoryProvider> providers = List.of(
                new ArtemisFactoryProvider(),
                new TibcoFactoryProvider(),
                new IbmMqFactoryProvider());

        String type = "unknown-mq";
        assertThat(providers.stream().anyMatch(p -> p.supports(type))).isFalse();
    }
}
