package com.echo.integration.jms;

import com.echo.integration.base.BaseIntegrationTest;
import com.echo.jms.JmsConnectionManager;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 從外部 broker（模擬公司 ESB）接收 JMS 請求：Echo 不啟動內建 Artemis，
 * 監聽外部 broker 上的 queue，並把回覆送到訊息的 JMSReplyTo。
 */
class ExternalJmsSourceIntegrationTest extends BaseIntegrationTest {

    private static final String QUEUE = "ESB.ECHO.IN";
    private static final int ESB_PORT = freePort();
    private static final int UNUSED_EMBEDDED_PORT = freePort();
    private static EmbeddedActiveMQ esb = startEsb();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JmsConnectionManager jmsConnectionManager;

    @DynamicPropertySource
    static void externalSource(DynamicPropertyRegistry registry) {
        registry.add("echo.jms.source.type", () -> "artemis");
        registry.add("echo.jms.source.server-url", () -> esbUrl());
        registry.add("echo.jms.source.reconnect-interval-ms", () -> "200");
        registry.add("echo.jms.queue", () -> QUEUE);
        registry.add("echo.jms.port", () -> String.valueOf(UNUSED_EMBEDDED_PORT));
        registry.add("echo.jms.concurrency", () -> "2-4");
    }

    @AfterAll
    static void stopEsb() throws Exception {
        if (esb != null) {
            esb.stop();
        }
    }

    @Test
    @DisplayName("外部模式不啟動內建 Artemis，也不開 echo.jms.port")
    void embeddedBrokerIsNotStarted() {
        assertThat(context.getBeanProvider(EmbeddedActiveMQ.class).getIfAvailable()).isNull();
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", UNUSED_EMBEDDED_PORT), 500);
            }
        }).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("系統狀態顯示外部來源、位址與接收中")
    @SuppressWarnings("unchecked")
    void statusShowsExternalSource() throws Exception {
        waitUntilReceiving();
        ResponseEntity<Map> status = adminClient().getForEntity("/api/admin/status", Map.class);

        assertThat(status.getBody()).containsEntry("jmsSourceType", "artemis")
                .containsEntry("artemisBrokerUrl", esbUrl())
                .containsEntry("jmsQueue", QUEUE)
                .containsEntry("jmsListenerReceiving", true);
    }

    @Test
    @DisplayName("ESB 上的請求由 Echo 處理，回覆送到 JMSReplyTo 並帶 correlation id")
    void requestOnEsbIsAnsweredOnReplyTo() throws Exception {
        createJmsRule(QUEUE, "<reply>from-echo</reply>");
        waitUntilReceiving();

        Reply reply = request("<req><ServiceName>Order</ServiceName></req>");

        assertThat(reply.body()).isEqualTo("<reply>from-echo</reply>");
        assertThat(reply.correlationId()).isEqualTo(reply.requestId());
    }

    @Test
    @DisplayName("ESB 重啟後 Echo 自動重新連線並繼續處理")
    void reconnectsAfterEsbRestart() throws Exception {
        createJmsRule(QUEUE, "<reply>again</reply>");
        waitUntilReceiving();
        assertThat(request("<req/>").body()).isEqualTo("<reply>again</reply>");

        esb.stop();
        waitUntil(() -> jmsConnectionManager.listenerState() == JmsConnectionManager.ListenerState.RECONNECTING, 10_000);
        esb = startEsb();
        waitUntilReceiving();

        assertThat(request("<req/>").body()).isEqualTo("<reply>again</reply>");
    }

    private record Reply(String requestId, String correlationId, String body) {
    }

    /** 模擬公司系統：送一則帶 JMSReplyTo 的請求到 ESB，等回覆。 */
    private static Reply request(String body) throws Exception {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(esbUrl());
        try (factory; Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(QUEUE);
            TemporaryQueue replyTo = session.createTemporaryQueue();
            MessageProducer producer = session.createProducer(queue);
            TextMessage message = session.createTextMessage(body);
            message.setJMSReplyTo(replyTo);
            producer.send(message);
            MessageConsumer consumer = session.createConsumer(replyTo);
            Message reply = consumer.receive(15_000);
            assertThat(reply).as("reply from Echo").isInstanceOf(TextMessage.class);
            return new Reply(message.getJMSMessageID(), reply.getJMSCorrelationID(), ((TextMessage) reply).getText());
        }
    }

    private void waitUntilReceiving() throws InterruptedException {
        waitUntil(() -> jmsConnectionManager.listenerState() == JmsConnectionManager.ListenerState.RECEIVING, 15_000);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("condition within %d ms", timeoutMs).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static String esbUrl() {
        return "tcp://127.0.0.1:" + ESB_PORT;
    }

    private static EmbeddedActiveMQ startEsb() {
        try {
            ConfigurationImpl configuration = new ConfigurationImpl();
            configuration.setPersistenceEnabled(false);
            configuration.setSecurityEnabled(false);
            configuration.addAcceptorConfiguration("tcp", esbUrl());
            EmbeddedActiveMQ broker = new EmbeddedActiveMQ();
            broker.setConfiguration(configuration);
            broker.start();
            return broker;
        } catch (Exception e) {
            throw new IllegalStateException("could not start test ESB broker", e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
