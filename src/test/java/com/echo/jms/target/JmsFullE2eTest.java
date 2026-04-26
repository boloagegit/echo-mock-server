package com.echo.jms.target;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import jakarta.jms.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完整 E2E 測試：Client → Echo (embedded Artemis 61616) → JmsTargetForwarder → Docker Artemis (61617) → 回應
 *
 * 前置條件：
 * 1. Docker Artemis 在 port 61617
 * 2. Echo 啟動且 echo.jms.target.enabled=true, type=artemis, server-url=tcp://localhost:61617
 */
class JmsFullE2eTest {

    private static final String ECHO_ARTEMIS_URL = "tcp://localhost:61616";
    private static final String TARGET_ARTEMIS_URL = "tcp://localhost:61617";
    private static final String ECHO_QUEUE = "ECHO.REQUEST";
    private static final String TARGET_QUEUE = "TARGET.REQUEST";

    static boolean isBothArtemisRunning() {
        return isPortOpen(61616) && isPortOpen(61617);
    }

    private static boolean isPortOpen(int port) {
        try (Socket s = new Socket("localhost", port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 完整 e2e 流程：
     * 1. 在 Docker Artemis (61617) 的 TARGET.REQUEST 啟動 consumer（模擬 target server）
     * 2. 發送 JMS 訊息到 Echo (61616) 的 ECHO.REQUEST
     * 3. Echo 無匹配規則 → JmsTargetForwarder 轉發到 Docker Artemis TARGET.REQUEST
     * 4. 模擬 target server 收到訊息，回覆到 JMSReplyTo
     * 5. JmsTargetForwarder 收到回覆，回傳給原始 caller
     */
    @Test
    @EnabledIf("isBothArtemisRunning")
    void fullE2e_echoForwardsToTargetAndReceivesReply() throws Exception {
        String requestBody = "<ServiceRequest><ServiceName>PaymentQuery</ServiceName><AccountId>12345</AccountId></ServiceRequest>";
        String expectedReply = "<ServiceResponse><Status>OK</Status><Balance>99999</Balance></ServiceResponse>";

        // Step 1: 在 Docker Artemis 啟動 target server consumer（背景執行緒）
        CompletableFuture<String> targetReceived = new CompletableFuture<>();

        Thread targetServer = new Thread(() -> {
            try {
                ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(TARGET_ARTEMIS_URL);
                factory.setUser("admin");
                factory.setPassword("admin");

                try (Connection conn = factory.createConnection()) {
                    conn.start();
                    try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                        Queue queue = session.createQueue(TARGET_QUEUE);
                        MessageConsumer consumer = session.createConsumer(queue);

                        // 等待 Echo 轉發過來的訊息
                        Message incoming = consumer.receive(15000);
                        if (incoming instanceof TextMessage tm) {
                            targetReceived.complete(tm.getText());

                            // 回覆到 JMSReplyTo
                            Destination replyTo = incoming.getJMSReplyTo();
                            if (replyTo != null) {
                                MessageProducer replier = session.createProducer(replyTo);
                                TextMessage reply = session.createTextMessage(expectedReply);
                                reply.setJMSCorrelationID(incoming.getJMSCorrelationID());
                                replier.send(reply);
                            }
                        } else {
                            targetReceived.complete("NO_MESSAGE");
                        }
                    }
                }
            } catch (Exception e) {
                targetReceived.completeExceptionally(e);
            }
        }, "target-server-simulator");
        targetServer.setDaemon(true);
        targetServer.start();

        // 等一下讓 consumer 準備好
        Thread.sleep(1000);

        // Step 2: 發送 JMS 訊息到 Echo 的 ECHO.REQUEST queue
        ActiveMQConnectionFactory echoFactory = new ActiveMQConnectionFactory(ECHO_ARTEMIS_URL);
        echoFactory.setUser("admin");
        echoFactory.setPassword("admin");

        String echoReply;
        try (Connection conn = echoFactory.createConnection()) {
            conn.start();
            try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue echoQueue = session.createQueue(ECHO_QUEUE);
                TemporaryQueue replyQueue = session.createTemporaryQueue();

                // 發送到 Echo
                MessageProducer producer = session.createProducer(echoQueue);
                TextMessage request = session.createTextMessage(requestBody);
                request.setJMSReplyTo(replyQueue);
                producer.send(request);

                // 等待 Echo 的回覆（Echo 轉發到 target → target 回覆 → Echo 回傳）
                MessageConsumer replyConsumer = session.createConsumer(replyQueue);
                Message reply = replyConsumer.receive(20000);

                if (reply instanceof TextMessage tm) {
                    echoReply = tm.getText();
                } else {
                    echoReply = "NO_REPLY";
                }
            }
        }

        // Step 3: 驗證
        // 3a. Target server 確實收到了 Echo 轉發的訊息
        String receivedByTarget = targetReceived.get(10, TimeUnit.SECONDS);
        assertThat(receivedByTarget).isEqualTo(requestBody);

        // 3b. Client 收到了 target server 的回覆（經由 Echo 轉發回來）
        assertThat(echoReply).isEqualTo(expectedReply);
    }
}
