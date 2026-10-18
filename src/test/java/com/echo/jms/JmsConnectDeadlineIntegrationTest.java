package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.ArtemisFactoryProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import jakarta.jms.Session;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JmsConnectDeadlineIntegrationTest {
    @TempDir Path data;
    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void aPeerThatAcceptsTcpWithoutCompletingTheBrokerHandshakeCannotHoldTheCallerForever(
            JmsForwardingRoute route) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try (var peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var accepted = executor.submit(peer::accept);
            var properties = new JmsProperties();
            var target = properties.getTarget();
            target.setEnabled(true);
            target.setType("artemis");
            target.setServerUrl("tcp://127.0.0.1:" + peer.getLocalPort()
                    + "?callTimeout=10000&initialConnectAttempts=50&reconnectAttempts=50");
            target.setTimeoutSeconds(1);
            var forwarder = route.createForwarder(properties, List.of(new ArtemisFactoryProvider()));
            try {
                var result = executor.submit(() -> route.forward(forwarder, "probe"));
                try (Socket connection = accepted.get(5, TimeUnit.SECONDS)) {
                    // The configured provider retry loop would otherwise wait for many minutes.
                    assertThat(result.get(4, TimeUnit.SECONDS)).contains("JMS forward error");
                }
                int port = peer.getLocalPort();
                peer.close();
                verifyRecoveryOnTheSameTarget(route, forwarder, port);
            } finally {
                forwarder.cleanup();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void verifyRecoveryOnTheSameTarget(JmsForwardingRoute route, JmsTargetForwarder forwarder,
                                               int port) throws Exception {
        String url = "tcp://127.0.0.1:" + port;
        var config = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJMXManagementEnabled(false).setJournalDirectory(data.resolve("journal").toString())
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setPagingDirectory(data.resolve("paging").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString())
                .addAcceptorConfiguration("recovered", url);
        var broker = ActiveMQServers.newActiveMQServer(config);
        var failure = new AtomicReference<Exception>();
        try {
            broker.start();
            try (var factory = new ActiveMQConnectionFactory(url);
                 var connection = factory.createConnection();
                 var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 var consumer = session.createConsumer(session.createQueue("TARGET.REQUEST"));
                 var producer = session.createProducer(null)) {
                consumer.setMessageListener(message -> {
                    try {
                        producer.send(message.getJMSReplyTo(), session.createTextMessage("recovered"));
                    } catch (Exception error) {
                        failure.set(error);
                    }
                });
                connection.start();
                assertThat(route.forward(forwarder, "after-failure")).isEqualTo("recovered");
                assertThat(broker.getActiveMQServerControl().getQueueNames()).containsExactly("TARGET.REQUEST");
                assertThat(failure.get()).isNull();
            }
        } finally {
            // End outbound usage before stopping the healthy broker to avoid introducing
            // another intentional network failure during test shutdown.
            forwarder.cleanup();
            broker.stop();
        }
    }
}
