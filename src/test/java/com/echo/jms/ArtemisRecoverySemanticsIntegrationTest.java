package com.echo.jms;

import jakarta.jms.JMSException;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.FailoverEventType;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptor;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnection;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.core.server.JournalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ArtemisRecoverySemanticsIntegrationTest {
    @TempDir Path data;

    @Test
    @Timeout(45)
    void terminalFailureUnblocksReceiveAndRollsBackUncommittedWork() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var fixture = new BrokerFixture(data)) {
            var notified = new CountDownLatch(2);
            var txNotifications = new AtomicInteger();
            var receiveNotifications = new AtomicInteger();
            try (var factory = fixture.factory("reconnectAttempts=0&failoverAttempts=0");
                 var txConnection = factory.createConnection();
                 var receiveConnection = factory.createConnection();
                 var tx = txConnection.createSession(true, Session.SESSION_TRANSACTED);
                 var receive = receiveConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 var producer = tx.createProducer(tx.createQueue("TX.COMMITTED"));
                 var consumer = tx.createConsumer(tx.createQueue("TX.COMMITTED"));
                 var waiting = receive.createConsumer(receive.createQueue("TX.WAIT"))) {
                txConnection.setExceptionListener(failure -> {
                    txNotifications.incrementAndGet();
                    notified.countDown();
                });
                receiveConnection.setExceptionListener(failure -> {
                    receiveNotifications.incrementAndGet();
                    notified.countDown();
                });
                txConnection.start();
                receiveConnection.start();
                var committed = tx.createTextMessage("committed");
                committed.setJMSCorrelationID("preserved-correlation");
                producer.send(committed);
                tx.commit();
                assertThat(((TextMessage) consumer.receive(5_000)).getText()).isEqualTo("committed");
                producer.send(tx.createTextMessage("uncommitted"));

                var receivingThread = new AtomicReference<Thread>();
                var pending = worker.submit(() -> {
                    receivingThread.set(Thread.currentThread());
                    try {
                        return waiting.receive(30_000);
                    } catch (JMSException expected) {
                        return expected;
                    }
                });
                await().atMost(Duration.ofSeconds(5)).until(() -> {
                    Thread thread = receivingThread.get();
                    return thread != null && !pending.isDone()
                            && (thread.getState() == Thread.State.WAITING
                            || thread.getState() == Thread.State.TIMED_WAITING);
                });
                fixture.broker.stop();
                Object result = pending.get(5, TimeUnit.SECONDS);
                assertThat(result == null || result instanceof JMSException).isTrue();
                assertThat(notified.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(txNotifications.get()).isEqualTo(1);
                assertThat(receiveNotifications.get()).isEqualTo(1);
            }
            fixture.assertNoHeartbeatWork();
            fixture.broker.start();
            try (var factory = fixture.factory("reconnectAttempts=0&failoverAttempts=0");
                 var connection = factory.createConnection();
                 var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                 var consumer = session.createConsumer(session.createQueue("TX.COMMITTED"))) {
                connection.start();
                var redelivered = (TextMessage) consumer.receive(5_000);
                assertThat(redelivered).isNotNull();
                assertThat(redelivered.getText()).isEqualTo("committed");
                assertThat(redelivered.getJMSCorrelationID()).isEqualTo("preserved-correlation");
                assertThat(redelivered.getJMSRedelivered()).isTrue();
                assertThat(consumer.receive(200)).as("uncommitted send must not survive the disconnect").isNull();
            }
            fixture.assertNoHeartbeatWork();
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(45)
    void configuredNativeReconnectPreservesTheConnectionAndRestartsHeartbeats() throws Exception {
        try (var fixture = new BrokerFixture(data);
             var factory = fixture.factory("reconnectAttempts=100&failoverAttempts=0&retryInterval=50"
                     + "&retryIntervalMultiplier=1&clientFailureCheckPeriod=100&connectionTTL=5000");
             var connection = factory.createConnection();
             var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
             var producer = session.createProducer(session.createQueue("NATIVE.RECOVERY"));
             var consumer = session.createConsumer(session.createQueue("NATIVE.RECOVERY"))) {
            var recovered = new CountDownLatch(1);
            var notifications = new AtomicInteger();
            connection.setExceptionListener(failure -> notifications.incrementAndGet());
            ((ActiveMQConnection) connection).getSessionFactory().addFailoverListener(event -> {
                if (event == FailoverEventType.FAILOVER_COMPLETED) {
                    recovered.countDown();
                }
            });
            connection.start();
            producer.send(session.createTextMessage("before"));
            assertThat(((TextMessage) consumer.receive(5_000)).getText()).isEqualTo("before");
            fixture.broker.stop();
            fixture.broker.start();
            assertThat(recovered.await(10, TimeUnit.SECONDS)).isTrue();
            producer.send(session.createTextMessage("after"));
            assertThat(((TextMessage) consumer.receive(5_000)).getText()).isEqualTo("after");
            // Artemis also reports the recovered transport interruption to ExceptionListener.
            // This is the unmodified client's behavior; successful recovery must preserve it.
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(notifications.get()).isEqualTo(1));
            assertThat(factory.getReconnectAttempts()).isEqualTo(100);
            long completed = fixture.scheduler.getCompletedTaskCount();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(fixture.scheduler.getQueue()).hasSize(1);
                assertThat(fixture.scheduler.getCompletedTaskCount()).isGreaterThan(completed + 1);
            });
        }
    }

    private static final class BrokerFixture implements AutoCloseable {
        private final ActiveMQServer broker;
        private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        private final int port;

        private BrokerFixture(Path data) throws Exception {
            var config = new ConfigurationImpl().setPersistenceEnabled(true).setSecurityEnabled(false)
                    .setJMXManagementEnabled(false).setJournalType(JournalType.NIO)
                    .setJournalFileSize(1024 * 1024).setJournalMinFiles(2).setJournalPoolFiles(2)
                    .setJournalDirectory(data.resolve("journal").toString())
                    .setBindingsDirectory(data.resolve("bindings").toString())
                    .setPagingDirectory(data.resolve("paging").toString())
                    .setLargeMessagesDirectory(data.resolve("large").toString())
                    .addAcceptorConfiguration("recovery", "tcp://127.0.0.1:0");
            broker = ActiveMQServers.newActiveMQServer(config);
            scheduler.setRemoveOnCancelPolicy(true);
            try {
                broker.start();
                port = ((NettyAcceptor) broker.getRemotingService().getAcceptor("recovery")).getActualPort();
                // The first bind is race-free; subsequent restarts retain the same client endpoint.
                config.getAcceptorConfigurations().iterator().next().getParams().put("port", port);
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        private ActiveMQConnectionFactory factory(String parameters) {
            var factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port
                    + "?initialConnectAttempts=0&callFailoverTimeout=3000&" + parameters);
            assertThat(factory.getServerLocator().setThreadPools(ActiveMQClient.getGlobalThreadPool(),
                    scheduler, ActiveMQClient.getGlobalFlowControlThreadPool())).isTrue();
            return factory;
        }

        private void assertNoHeartbeatWork() {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(scheduler.getQueue()).isEmpty());
        }

        @Override
        public void close() throws Exception {
            try {
                assertNoHeartbeatWork();
            } finally {
                try {
                    broker.stop();
                } finally {
                    scheduler.shutdownNow();
                    assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }
}
