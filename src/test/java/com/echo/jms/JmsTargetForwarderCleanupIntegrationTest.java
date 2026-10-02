package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.ArtemisFactoryProvider;
import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real broker regression: closing a Session alone must not leave reply destinations behind. */
class JmsTargetForwarderCleanupIntegrationTest {
    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void diagnosticTraceDistinguishesSuccessTimeoutThenRecoveryOnRealBroker(JmsForwardingRoute route) throws Exception {
        var lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try (var diagnostics = new com.echo.diagnostics.TransactionDiagnostics(
                new com.echo.config.DiagnosticProperties(), lines::add)) {
            diagnostics.start();
            var properties = new JmsProperties();
            properties.getTarget().setEnabled(true);
            properties.getTarget().setType("artemis");
            properties.getTarget().setServerUrl(BROKER_URL);
            properties.getTarget().setQueue(REQUEST_QUEUE);
            properties.getTarget().setTimeoutSeconds(1);
            forwarder = route.createForwarder(properties, List.of(new ArtemisFactoryProvider()));
            for (String body : List.of("request", "timeout", "request")) {
                var trace = diagnostics.begin("JMS");
                assertThat(route.forward(forwarder, body, trace)).isEqualTo(body.equals("timeout")
                        ? "<error>JMS response timeout</error>" : "reply");
                trace.end("PROCESSING_ENDED");
                assertThat(broker.getActiveMQServerControl().getQueueNames()).containsExactly(REQUEST_QUEUE);
            }
            org.awaitility.Awaitility.await().atMost(2, java.util.concurrent.TimeUnit.SECONDS)
                    .until(() -> lines.size() == 1);
            assertThat(lines.get(0)).contains("waitResult=NO_REPLY", "sendReturned=true", "outId=ID:", "outReplyTo=");
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticAccepted", 1L);
            assertThat(diagnostics.snapshot()).containsEntry("diagnosticDropped", 0L);
        }
    }
    private static final String BROKER_URL = "vm://9462";
    private static final String REQUEST_QUEUE = "CLEANUP.REQUEST";

    @TempDir
    Path data;

    private ActiveMQServer broker;
    private ActiveMQConnectionFactory responderFactory;
    private Connection responder;
    private JmsTargetForwarder forwarder;
    private final AtomicReference<Exception> responderFailure = new AtomicReference<>();

    @BeforeEach
    void startBroker() throws Exception {
        var configuration = new ConfigurationImpl();
        configuration.setPersistenceEnabled(false).setSecurityEnabled(false).setJMXManagementEnabled(false)
                .setJournalDirectory(data.resolve("journal").toString())
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setPagingDirectory(data.resolve("paging").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString())
                .addAcceptorConfiguration("cleanup-test", BROKER_URL);
        broker = ActiveMQServers.newActiveMQServer(configuration);
        broker.start();
        responderFactory = new ActiveMQConnectionFactory(BROKER_URL);
        responder = responderFactory.createConnection();
        Session session = responder.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer consumer = session.createConsumer(session.createQueue(REQUEST_QUEUE));
        MessageProducer producer = session.createProducer(null);
        consumer.setMessageListener(message -> {
            try {
                if ("timeout".equals(((TextMessage) message).getText())) return;
                producer.send(message.getJMSReplyTo(), session.createTextMessage("reply"));
            } catch (Exception failure) {
                responderFailure.set(failure);
            }
        });
        responder.start();
    }

    @AfterEach
    void stopBroker() throws Exception {
        try {
            if (forwarder != null) forwarder.cleanup();
            if (responder != null) responder.close();
        } finally {
            try {
                if (responderFactory != null) responderFactory.close();
            } finally {
                if (broker != null) broker.stop();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void repliesAndTimeoutsLeaveNoTemporaryQueuesWhileConnectionStaysOpen(
            JmsForwardingRoute route) throws Exception {
        JmsProperties props = new JmsProperties();
        var target = props.getTarget();
        target.setEnabled(true);
        target.setType("artemis");
        target.setServerUrl(BROKER_URL);
        target.setQueue(REQUEST_QUEUE);
        target.setTimeoutSeconds(1);
        forwarder = route.createForwarder(props, List.of(new ArtemisFactoryProvider()));

        for (int n = 0; n < 25; n++) {
            assertThat(route.forward(forwarder, "request")).isEqualTo("reply");
            assertThat(broker.getActiveMQServerControl().getQueueNames()).containsExactly(REQUEST_QUEUE);
        }
        assertThat(route.forward(forwarder, "timeout")).isEqualTo("<error>JMS response timeout</error>");
        assertThat(broker.getActiveMQServerControl().getQueueNames()).containsExactly(REQUEST_QUEUE);
        assertThat(route.forward(forwarder, "after-timeout")).isEqualTo("reply");
        assertThat(broker.getActiveMQServerControl().getQueueNames()).containsExactly(REQUEST_QUEUE);
        assertThat(responderFailure.get()).isNull();
        // Responder + outbound connections still exist: cleanup was per request, not on shutdown.
        assertThat(broker.getActiveMQServerControl().getConnectionCount()).isEqualTo(2);
    }
}
