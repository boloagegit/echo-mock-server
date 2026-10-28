package com.echo.integration.jms;

import com.echo.agent.LogAgent;
import com.echo.dto.JmsTargetConnectionRequest;
import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.jms.JmsTargetForwarder;
import com.echo.repository.RequestLogRepository;
import com.echo.service.JmsTargetConnectionService;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real source delivery, forwarding, replies and durable logging across management DB failures. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=3",
        "spring.datasource.hikari.connection-timeout=500", "echo.jms.concurrency=8-8",
        "echo.jms.target.enabled=false", "echo.jms.target-catalog-enabled=true",
        "echo.request-log.store=database", "echo.request-log.max-records=1000",
        "echo.agent.log.batch-size=1"
})
class JmsTargetDatabaseRecoveryIntegrationTest extends BaseIntegrationTest {
    @TempDir static Path directory;
    @Autowired private DataSource dataSource;
    @Autowired private ConnectionFactory sourceFactory;
    @Autowired private EmbeddedActiveMQ sourceBroker;
    @Autowired private JmsTargetConnectionService targets;
    @Autowired private JmsTargetForwarder forwarder;
    @Autowired private LogAgent agent;
    @Autowired private RequestLogRepository logs;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("main.sqlite")
                + "?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL&foreign_keys=ON");
        registry.add("echo.request-log.durable.spool-path", () -> directory.resolve("spool.sqlite").toString());
    }

    @Test
    @Timeout(90)
    void warmForwardingSurvivesExhaustionColdFailureDoesNotSendAndRecoveryUsesNewSettings() throws Exception {
        String url = "vm://98245";
        var config = new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .setJMXManagementEnabled(false).setJournalDirectory(directory.resolve("journal").toString())
                .setBindingsDirectory(directory.resolve("bindings").toString())
                .setPagingDirectory(directory.resolve("paging").toString())
                .setLargeMessagesDirectory(directory.resolve("large").toString())
                .addAcceptorConfiguration("db-recovery", url);
        ActiveMQServer target = ActiveMQServers.newActiveMQServer(config);
        target.start();
        var targetFailure = new AtomicReference<Throwable>();
        try (var targetFactory = new ActiveMQConnectionFactory(url);
             var responder = targetFactory.createConnection();
             var first = responder.createSession(true, Session.SESSION_TRANSACTED);
             var second = responder.createSession(true, Session.SESSION_TRANSACTED);
             var source = sourceFactory.createConnection()) {
            echo(first, "DB.TARGET.A", targetFailure);
            echo(second, "DB.TARGET.B", targetFailure);
            responder.start();
            source.start();
            var profile = targets.create(new JmsTargetConnectionRequest(null, "DB recovery", "artemis",
                    url, null, null, false, "DB.TARGET.A", 2, true, true));
            var rule = RuleDto.builder().protocol(Protocol.JMS).matchKey("ECHO.REQUEST")
                    .enabled(true).action("FORWARD").forwardTargetMode("CONNECTION")
                    .jmsTargetConnectionId(profile.id()).build();
            assertThat(adminClient().postForEntity("/api/admin/rules", rule, RuleDto.class)
                    .getStatusCode().is2xxSuccessful()).isTrue();

            batch(source, "warm", "DB.TARGET.A", false);
            persisted(8);
            List<java.sql.Connection> held = holdPool();
            try {
                batch(source, "pool-hot", "DB.TARGET.A", false);
                await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                        assertThat(agent.getStats().getQueueSize()).isPositive());
            } finally { release(held); }
            persisted(16);

            targets.update(Long.valueOf(profile.id()), new JmsTargetConnectionRequest(profile.version(),
                    profile.name(), "artemis", url, null, null, false, "DB.TARGET.B", 2, true, true));
            held = holdPool();
            long began = System.nanoTime();
            try {
                batch(source, "pool-cold", null, true);
                assertThat(target.locateQueue(SimpleString.of("DB.TARGET.A")).getMessagesAdded()).isEqualTo(16);
                assertThat(target.locateQueue(SimpleString.of("DB.TARGET.B")).getMessagesAdded()).isZero();
            } finally { release(held); }
            long coldFailureMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            batch(source, "recovered", "DB.TARGET.B", false);
            persisted(32);

            try (var blocker = dataSource.getConnection(); var sql = blocker.createStatement()) {
                sql.execute("BEGIN IMMEDIATE");
                try {
                    batch(source, "write-lock", "DB.TARGET.B", false);
                    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                            assertThat(agent.getStats().getQueueSize()).isPositive());
                    assertThat(agent.getStats().getProcessedCount()).isEqualTo(32);
                } finally { sql.execute("ROLLBACK"); }
            }
            persisted(40);
            assertThat(logs.findAll()).extracting(row -> row.getRequestBody()).doesNotHaveDuplicates();
            assertThat(logs.findAll().stream().filter(row -> row.getResponseBody()
                    .startsWith("<error>JMS forward error:")).count()).isEqualTo(8);
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                var sourceQueue = sourceBroker.getActiveMQServer().locateQueue(SimpleString.of("ECHO.REQUEST"));
                assertThat(sourceQueue.getMessagesAcknowledged()).isEqualTo(40);
                assertThat(sourceQueue.getMessageCount()).isZero();
                assertThat(sourceQueue.getDeliveringCount()).isZero();
                assertThat(target.locateQueue(SimpleString.of("DB.TARGET.A")).getMessagesAcknowledged()).isEqualTo(16);
                assertThat(target.locateQueue(SimpleString.of("DB.TARGET.B")).getMessagesAcknowledged()).isEqualTo(16);
            });
            assertThat(targetFailure.get()).isNull();
            System.out.println("JMS_DB_RECOVERY checkedReplies=40 expectedDbErrorReplies=8 targetDeliveries=32"
                    + " durableLogs=40 coldFailureBatchMillis=" + coldFailureMillis);
        } finally {
            forwarder.cleanup();
            target.stop();
        }
    }

    private List<java.sql.Connection> holdPool() throws Exception {
        var held = new ArrayList<java.sql.Connection>();
        try {
            for (int i = 0; i < 3; i++) held.add(dataSource.getConnection());
            return held;
        } catch (Exception failure) {
            release(held);
            throw failure;
        }
    }

    private static void release(List<java.sql.Connection> held) throws Exception {
        for (var connection : held) connection.close();
    }

    private void persisted(int count) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(agent.getStats().getQueueSize()).isZero();
            assertThat(agent.getStats().getProcessedCount()).isEqualTo(count);
            assertThat(agent.getStats().getDroppedCount()).isZero();
            assertThat(logs.count()).isEqualTo(count);
        });
    }

    private static void echo(Session session, String queue, AtomicReference<Throwable> failure) throws Exception {
        var consumer = session.createConsumer(session.createQueue(queue));
        var producer = session.createProducer(null);
        consumer.setMessageListener(message -> {
            try {
                var reply = session.createTextMessage(queue + ":" + ((TextMessage) message).getText());
                reply.setJMSCorrelationID(message.getJMSCorrelationID());
                producer.send(message.getJMSReplyTo(), reply);
                session.commit();
            } catch (Exception error) {
                failure.compareAndSet(null, error);
                try { session.rollback(); }
                catch (Exception rollbackFailure) { error.addSuppressed(rollbackFailure); }
            }
        });
    }

    private static void batch(Connection source, String phase, String queueName, boolean expectedFailure) throws Exception {
        var workers = Executors.newFixedThreadPool(8);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try {
            var calls = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                String body = phase + ":" + i;
                calls.add(workers.submit(() -> {
                    try (var session = source.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                        var replyQueue = session.createTemporaryQueue();
                        try (var producer = session.createProducer(session.createQueue("ECHO.REQUEST"));
                             var consumer = session.createConsumer(replyQueue)) {
                            ready.countDown();
                            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Batch start timed out");
                            var request = session.createTextMessage(body);
                            request.setJMSReplyTo(replyQueue);
                            request.setJMSCorrelationID("caller-" + body);
                            producer.send(request);
                            var reply = consumer.receive(5000);
                            assertThat(reply).isInstanceOf(TextMessage.class);
                            assertThat(reply.getJMSCorrelationID()).isEqualTo(request.getJMSMessageID());
                            String actual = ((TextMessage) reply).getText();
                            if (expectedFailure) assertThat(actual).startsWith("<error>JMS forward error:");
                            else assertThat(actual).isEqualTo(queueName + ":" + body);
                        } finally { replyQueue.delete(); }
                    } catch (Exception failure) { throw new AssertionError(failure); }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var call : calls) call.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
