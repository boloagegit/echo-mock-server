package com.echo.integration.jms;

import com.echo.agent.LogAgent;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.RequestLogRepository;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** A real main-SQLite write lock must not block cached mock replies or lose spool records. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"echo.request-log.store=database", "echo.request-log.max-records=10000",
        "echo.agent.log.batch-size=1"})
class JmsDatabaseLockRecoveryIntegrationTest extends BaseIntegrationTest {
    @TempDir static Path directory;
    @Autowired private DataSource datasource;
    @Autowired private ConnectionFactory connectionFactory;
    @Autowired private LogAgent agent;
    @Autowired private RequestLogRepository logs;

    @DynamicPropertySource
    static void spool(DynamicPropertyRegistry registry) {
        registry.add("echo.request-log.durable.spool-path", () -> directory.resolve("spool.sqlite").toString());
    }

    @Test
    void cachedHttpAndJmsContinueWhileMainDbIsLockedThenEveryLogIsPersistedOnce() throws Exception {
        createHttpRule("/database-lock/http", "GET", "http-ok");
        createJmsRule("ECHO.REQUEST", "jms-ok");
        try (var connection = connectionFactory.createConnection()) {
            connection.start();
            try (var session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                assertReplies(session);
                await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                    assertThat(agent.getStats().getQueueSize()).isZero();
                    assertThat(logs.count()).isEqualTo(2);
                });
                try (var blocked = datasource.getConnection(); var sql = blocked.createStatement()) {
                    sql.execute("BEGIN IMMEDIATE");
                    try {
                        for (int i = 0; i < 5; i++) assertReplies(session);
                        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                                assertThat(agent.getStats().getQueueSize()).isPositive());
                        assertThat(agent.getStats().getProcessedCount()).isEqualTo(2);
                    } finally {
                        sql.execute("ROLLBACK");
                    }
                }
                await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
                    assertThat(agent.getStats().getQueueSize()).isZero();
                    assertThat(logs.count()).isEqualTo(12);
                    assertThat(agent.getStats().getProcessedCount()).isEqualTo(12);
                    assertThat(agent.getStats().getDroppedCount()).isZero();
                });
            }
        }
    }

    private void assertReplies(Session session) throws Exception {
        assertThat(restTemplate.getForObject("/mock/database-lock/http", String.class)).isEqualTo("http-ok");
        var queue = session.createTemporaryQueue();
        try (var producer = session.createProducer(session.createQueue("ECHO.REQUEST"));
             var consumer = session.createConsumer(queue)) {
            var request = session.createTextMessage("<Request/>");
            request.setJMSReplyTo(queue);
            producer.send(request);
            var reply = consumer.receive(2000);
            assertThat(reply).isInstanceOf(TextMessage.class);
            assertThat(((TextMessage) reply).getText()).isEqualTo("jms-ok");
            assertThat(reply.getJMSCorrelationID()).isEqualTo(request.getJMSMessageID());
        } finally {
            queue.delete();
        }
    }
}
