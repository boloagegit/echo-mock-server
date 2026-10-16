package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.ArtemisFactoryProvider;
import jakarta.jms.*;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CachedJmsForwardingIntegrationTest {
    @TempDir Path data;

    @Test
    void exclusiveCachedSessionsKeepUncorrelatedDuplicateRepliesIsolatedAndCloseOnEviction() throws Exception {
        String url = "vm://9473";
        var config = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJMXManagementEnabled(false).setJournalDirectory(data.resolve("journal").toString())
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setPagingDirectory(data.resolve("paging").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString())
                .addAcceptorConfiguration("cached-test", url);
        var broker = ActiveMQServers.newActiveMQServer(config);
        broker.start();
        var properties = new JmsProperties();
        properties.getForward().setSessionCacheSize(4);
        var target = properties.getTarget();
        target.setEnabled(true);
        target.setType("artemis");
        target.setServerUrl(url);
        target.setQueue("CACHE.REQUEST");
        target.setTimeoutSeconds(2);
        var failure = new AtomicReference<Throwable>();
        var executor = Executors.newFixedThreadPool(4);
        try (var factory = new ActiveMQConnectionFactory(url);
             var responder = factory.createConnection();
             var session = responder.createSession(true, Session.SESSION_TRANSACTED)) {
            var consumer = session.createConsumer(session.createQueue("CACHE.REQUEST"));
            var producer = session.createProducer(null);
            consumer.setMessageListener(message -> {
                try {
                    // Send duplicates atomically, before the forwarder can delete the queue. No
                    // correlation ID: supported legacy servers rely on the unique reply address.
                    String body = ((TextMessage) message).getText();
                    producer.send(message.getJMSReplyTo(), session.createTextMessage(body));
                    producer.send(message.getJMSReplyTo(), session.createTextMessage("duplicate:" + body));
                    session.commit();
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            responder.start();
            int responderSessions = broker.getSessions().size();
            for (JmsForwardingRoute route : JmsForwardingRoute.values()) {
                var forwarder = route.createForwarder(properties, List.of(new ArtemisFactoryProvider()));
                try {
                    List<Future<?>> tasks = new ArrayList<>();
                    for (int worker = 0; worker < 4; worker++) {
                        int id = worker;
                        tasks.add(executor.submit(() -> {
                            for (int n = 0; n < 40; n++) {
                                String request = route + ":" + id + ":" + n;
                                assertThat(route.forward(forwarder, request)).isEqualTo(request);
                            }
                        }));
                    }
                    for (var task : tasks) task.get(30, TimeUnit.SECONDS);
                    assertThat(failure.get()).isNull();
                    assertThat(broker.getActiveMQServerControl().getQueueNames()).containsExactly("CACHE.REQUEST");
                    assertThat(broker.getActiveMQServerControl().getConnectionCount()).isEqualTo(2);
                    // Artemis also owns one internal metadata session per connection.
                    assertThat(broker.getSessions().size()).isBetween(responderSessions + 2, responderSessions + 5);
                    if (route != JmsForwardingRoute.LEGACY) {
                        forwarder.evict(7L);
                        assertThat(broker.getActiveMQServerControl().getConnectionCount()).isEqualTo(1);
                        assertThat(route.forward(forwarder, "after-eviction")).isEqualTo("after-eviction");
                    }
                } finally { forwarder.cleanup(); }
                assertThat(broker.getActiveMQServerControl().getConnectionCount()).isEqualTo(1);
                assertThat(broker.getSessions()).hasSize(responderSessions);
            }
            consumer.close();
            producer.close();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            broker.stop();
        }
    }
}
