package com.echo.config;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Large TextMessages must survive broker restart and transactional redelivery. */
class ArtemisLargeMessageIntegrationTest {

    private static final String URL = "vm://7532";
    private static final String QUEUE = "large-message.restart";

    @TempDir
    Path directory;

    @Test
    @Timeout(60)
    void persistentUnicodeTextSurvivesRestartAndRollback() throws Exception {
        // Exceeds the default 100 KiB large-message threshold, including Unicode,
        // surrogate pairs and characters which are escaped by request logging.
        String body = "付款🧪\\\"\n".repeat(20_000);
        String messageId;
        EmbeddedActiveMQ broker = startBroker();
        try {
            try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(URL);
                 Connection connection = factory.createConnection();
                 Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
                 MessageProducer producer = session.createProducer(session.createQueue(QUEUE))) {
                TextMessage message = session.createTextMessage(body);
                message.setJMSCorrelationID("large-message-correlation");
                message.setStringProperty("branch", "台北");
                producer.send(message, DeliveryMode.PERSISTENT, 4, 0);
                messageId = message.getJMSMessageID();
                session.commit();
            }
        } finally {
            broker.stop();
        }

        broker = startBroker();
        try {
            try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(URL);
                 Connection connection = factory.createConnection();
                 Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
                 MessageConsumer consumer = session.createConsumer(session.createQueue(QUEUE))) {
                connection.start();
                TextMessage first = (TextMessage) consumer.receive(10_000);
                assertThat(first).isNotNull();
                assertThat(first.getText()).isEqualTo(body);
                session.rollback();

                TextMessage redelivered = (TextMessage) consumer.receive(10_000);
                assertThat(redelivered).isNotNull();
                assertThat(redelivered.getText()).isEqualTo(body);
                assertThat(redelivered.getJMSMessageID()).isEqualTo(messageId);
                assertThat(redelivered.getJMSCorrelationID()).isEqualTo("large-message-correlation");
                assertThat(redelivered.getStringProperty("branch")).isEqualTo("台北");
                assertThat(redelivered.getJMSDeliveryMode()).isEqualTo(DeliveryMode.PERSISTENT);
                assertThat(redelivered.getJMSRedelivered()).isTrue();
                assertThat(redelivered.getIntProperty("JMSXDeliveryCount")).isGreaterThanOrEqualTo(2);
                session.commit();
                assertThat(consumer.receiveNoWait()).isNull();
            }
        } finally {
            broker.stop();
        }
    }

    private EmbeddedActiveMQ startBroker() throws Exception {
        Configuration configuration = new ConfigurationImpl()
                .setPersistenceEnabled(true)
                .setJournalType(JournalType.NIO)
                .setSecurityEnabled(false)
                .setJMXManagementEnabled(false)
                .setBindingsDirectory(directory.resolve("bindings").toString())
                .setJournalDirectory(directory.resolve("journal").toString())
                .setPagingDirectory(directory.resolve("paging").toString())
                .setLargeMessagesDirectory(directory.resolve("large-messages").toString())
                .setLargeMessageSync(true)
                .addAcceptorConfiguration("large-message-test", URL);
        return new EmbeddedActiveMQ().setConfiguration(configuration).start();
    }
}
