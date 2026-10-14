package com.echo.config;

import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真實 broker：客戶端在同一條長連線上每次請求都建新的暫時回覆佇列卻不刪時，
 * 沒有 consumer 的佇列要被刪掉，仍在使用的佇列與其他設定不受影響。
 */
class ArtemisTemporaryQueueCleanupIntegrationTest {

    private static final String BROKER_URL = "vm://9471";
    private static final long IDLE_DELETE_MS = 300;

    @TempDir
    Path data;

    private ActiveMQServer broker;
    private Connection connection;

    @AfterEach
    void stop() throws Exception {
        if (connection != null) {
            connection.close();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    @DisplayName("沒有 consumer 的暫時佇列閒置後被刪除；仍在聽的佇列保留")
    void idleTemporaryQueuesAreDeletedWhileListenedQueuesStay() throws Exception {
        Session session = start(IDLE_DELETE_MS);
        TemporaryQueue abandoned = session.createTemporaryQueue();
        session.createConsumer(abandoned).close();
        TemporaryQueue abandonedWithLateReply = session.createTemporaryQueue();
        session.createConsumer(abandonedWithLateReply).close();
        session.createProducer(abandonedWithLateReply).send(session.createTextMessage("late reply"));
        TemporaryQueue listened = session.createTemporaryQueue();
        MessageConsumer listener = session.createConsumer(listened);

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> !exists(abandoned) && !exists(abandonedWithLateReply));
        // 佇列的 address（以及它的分頁物件）在下一次掃描時一併移除
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> broker.getAddressInfo(SimpleString.of(abandoned.getQueueName())) == null);
        assertThat(exists(listened)).isTrue();

        session.createProducer(listened).send(session.createTextMessage("reply"));
        Message reply = listener.receive(5_000);
        assertThat(reply).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) reply).getText()).isEqualTo("reply");
    }

    @Test
    @DisplayName("客戶端之後再聽已刪除的暫時佇列時會自動重建，仍收得到回覆")
    void deletedTemporaryQueueIsRecreatedWhenTheClientListensAgain() throws Exception {
        Session session = start(IDLE_DELETE_MS);
        TemporaryQueue reused = session.createTemporaryQueue();
        session.createConsumer(reused).close();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !exists(reused));

        MessageConsumer again = session.createConsumer(reused);
        session.createProducer(reused).send(session.createTextMessage("reply"));

        Message reply = again.receive(5_000);
        assertThat(reply).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) reply).getText()).isEqualTo("reply");
    }

    @Test
    @DisplayName("暫時佇列仍套用 # 的分頁與記憶體設定；一般佇列不會被設為自動刪除")
    void temporaryQueuesKeepTheWildcardPolicy() throws Exception {
        Session session = start(IDLE_DELETE_MS);
        TemporaryQueue temporary = session.createTemporaryQueue();
        session.createConsumer(temporary);

        var settings = broker.getAddressSettingsRepository();
        var wildcard = settings.getMatch("ECHO.REQUEST");
        var forTemporary = settings.getMatch(
                ArtemisConfig.TEMPORARY_QUEUE_NAMESPACE + "." + temporary.getQueueName());
        assertThat(forTemporary.getMaxSizeBytes()).isEqualTo(wildcard.getMaxSizeBytes());
        assertThat(forTemporary.getAddressFullMessagePolicy()).isEqualTo(wildcard.getAddressFullMessagePolicy());
        assertThat(forTemporary.getDefaultConsumerWindowSize()).isEqualTo(wildcard.getDefaultConsumerWindowSize());
        assertThat(forTemporary.getRedeliveryDelay()).isEqualTo(wildcard.getRedeliveryDelay());
        assertThat(forTemporary.getAutoDeleteQueuesDelay()).isEqualTo(IDLE_DELETE_MS);
        assertThat(wildcard.isAutoDeleteCreatedQueues()).isFalse();
    }

    @Test
    @DisplayName("設為 0 時不自動刪除，維持連線關閉才回收")
    void zeroKeepsTemporaryQueuesUntilTheConnectionCloses() throws Exception {
        Session session = start(0);
        TemporaryQueue abandoned = session.createTemporaryQueue();
        session.createConsumer(abandoned).close();

        Thread.sleep(IDLE_DELETE_MS * 4);

        assertThat(exists(abandoned)).isTrue();
    }

    private Session start(long idleDeleteMs) throws Exception {
        JmsProperties properties = new JmsProperties();
        properties.setTemporaryQueueIdleDeleteMs(idleDeleteMs);
        ConfigurationImpl configuration = new ConfigurationImpl();
        new ArtemisConfig().artemisConfigurationCustomizer(properties).customize(configuration);
        // 只用 in-VM，不開 TCP port；縮短掃描間隔讓測試不必等預設的 30 秒
        configuration.getAcceptorConfigurations().clear();
        configuration.addAcceptorConfiguration("cleanup-test", BROKER_URL);
        configuration.setAddressQueueScanPeriod(100);
        configuration.setPersistenceEnabled(false).setSecurityEnabled(false).setJMXManagementEnabled(false)
                .setJournalDirectory(data.resolve("journal").toString())
                .setBindingsDirectory(data.resolve("bindings").toString())
                .setPagingDirectory(data.resolve("paging").toString())
                .setLargeMessagesDirectory(data.resolve("large").toString());
        broker = ActiveMQServers.newActiveMQServer(configuration);
        broker.start();
        connection = new ActiveMQConnectionFactory(BROKER_URL).createConnection();
        connection.start();
        return connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    }

    private boolean exists(TemporaryQueue queue) throws Exception {
        return broker.locateQueue(SimpleString.of(queue.getQueueName())) != null;
    }
}
