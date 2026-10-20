package com.echo.jms;

import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptor;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ArtemisHeartbeatCleanupIntegrationTest {
    @TempDir Path data;

    @Test
    @Timeout(45)
    void terminalDisconnectCancelsHeartbeatAndStillNotifiesTheJmsClient() throws Exception {
        var config = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJMXManagementEnabled(false)
                .setJournalDirectory(data.resolve("journal").toString())
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setPagingDirectory(data.resolve("paging").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString())
                .addAcceptorConfiguration("heartbeat", "tcp://127.0.0.1:0");
        var broker = ActiveMQServers.newActiveMQServer(config);
        // Inspect only this test's scheduler; never clear or purge the shared client pool.
        var scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        try {
            for (int cycle = 0; cycle < 3; cycle++) {
                broker.start();
                int port = ((NettyAcceptor) broker.getRemotingService().getAcceptor("heartbeat")).getActualPort();
                try (var factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port
                        + "?initialConnectAttempts=0&reconnectAttempts=0&failoverAttempts=0")) {
                    assertThat(factory.getServerLocator().setThreadPools(ActiveMQClient.getGlobalThreadPool(),
                            scheduler, ActiveMQClient.getGlobalFlowControlThreadPool())).isTrue();
                    var notified = new CountDownLatch(1);
                    var notifications = new AtomicInteger();
                    try (var connection = factory.createConnection();
                         var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                         var consumer = session.createConsumer(session.createQueue("HEARTBEAT.REQUEST"))) {
                        connection.setExceptionListener(failure -> {
                            notifications.incrementAndGet();
                            notified.countDown();
                        });
                        connection.start();
                        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                                assertThat(scheduler.getQueue()).hasSize(1));
                        broker.stop();
                        assertThat(notified.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                    assertThat(notifications.get()).isEqualTo(1);
                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                            assertThat(scheduler.getQueue()).as("terminal heartbeat work must be cancelled").isEmpty());
                }
            }
        } finally {
            broker.stop();
            scheduler.shutdownNow();
            assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
