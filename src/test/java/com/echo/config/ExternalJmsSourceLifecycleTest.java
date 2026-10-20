package com.echo.config;

import com.echo.jms.target.ArtemisFactoryProvider;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptor;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jms.connection.CachingConnectionFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ExternalJmsSourceLifecycleTest {
    @TempDir Path data;

    @Test
    @Timeout(45)
    void repeatedContextsCloseProviderFactoriesButConnectionRecoveryKeepsThemUsable() throws Exception {
        var configuration = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJMXManagementEnabled(false)
                .setJournalDirectory(data.resolve("journal").toString())
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setPagingDirectory(data.resolve("paging").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString())
                .addAcceptorConfiguration("source-lifecycle", "tcp://127.0.0.1:0");
        var broker = ActiveMQServers.newActiveMQServer(configuration);
        try {
            broker.start();
            int port = ((NettyAcceptor) broker.getRemotingService().getAcceptor("source-lifecycle")).getActualPort();
            assertThat(port).isPositive();
            String url = "tcp://127.0.0.1:" + port;
            for (int cycle = 0; cycle < 3; cycle++) {
                ActiveMQConnectionFactory provider = null;
                try {
                    try (var context = new AnnotationConfigApplicationContext()) {
                        context.registerBean("jmsConnectionFactory", ConnectionFactory.class, () -> createSource(url));
                        context.refresh();
                        var caching = (CachingConnectionFactory) context.getBean(ConnectionFactory.class);
                        provider = (ActiveMQConnectionFactory) Objects.requireNonNull(caching.getTargetConnectionFactory());
                        useConnection(caching);
                        caching.resetConnection();
                        assertThat(provider.getServerLocator().isClosed()).as("recovery must keep the provider").isFalse();
                        useConnection(caching);
                    }
                    assertThat(provider.getServerLocator().isClosed()).as("context close must close its provider").isTrue();
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(broker.getActiveMQServerControl().getConnectionCount()).isZero();
                        assertThat(broker.getSessions()).isEmpty();
                    });
                } finally {
                    // The pre-fix failure must not leak the very factory this regression exposes.
                    if (provider != null && !provider.getServerLocator().isClosed()) provider.close();
                }
            }
        } finally {
            broker.stop();
        }
    }

    private static void useConnection(ConnectionFactory factory) throws Exception {
        try (var connection = factory.createConnection();
             var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
             var consumer = session.createConsumer(session.createQueue("SOURCE.LIFECYCLE"))) {
            connection.start();
        }
    }

    private static ConnectionFactory createSource(String url) {
        var properties = new JmsProperties();
        properties.getSource().setType("artemis");
        properties.getSource().setServerUrl(url);
        try {
            return new ExternalJmsSourceConfig().jmsConnectionFactory(properties, List.of(new ArtemisFactoryProvider()));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not create test source factory", failure);
        }
    }
}
