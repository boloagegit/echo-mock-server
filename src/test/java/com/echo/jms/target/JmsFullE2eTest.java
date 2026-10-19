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
 * Complete E2E test: Client → Echo (embedded Artemis 61616) → JmsTargetForwarder → Docker Artemis (61617) → Response
 *
 * Prerequisites:
 * 1. Docker Artemis in port 61617
 * 2. Echo starts and echo.jms.target.enabled=true, type=artemis, server-url=tcp://localhost:61617
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
     * Complete e2e process:
     * 1. Start consumer in TARGET.REQUEST of Docker Artemis (61617) (emulates target server)
     * 2. Send JMS message to ECHO.REQUEST of Echo (61616)
     * 3. Echo has no matching rule → JmsTargetForwarder is forwarded to Docker Artemis TARGET.REQUEST
     * 4. Simulate target server receives the message and replies to JMSReplyTo
     * 5. JmsTargetForwarder receives the reply and sends it back to the original caller
     */
    @Test
    @EnabledIf("isBothArtemisRunning")
    void fullE2e_echoForwardsToTargetAndReceivesReply() throws Exception {
        String requestBody = "<ServiceRequest><ServiceName>PaymentQuery</ServiceName><AccountId>12345</AccountId></ServiceRequest>";
        String expectedReply = "<ServiceResponse><Status>OK</Status><Balance>99999</Balance></ServiceResponse>";

        // Step 1: Start target server consumer in Docker Artemis (background thread)
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

                        // Waiting for the message forwarded by Echo
                        Message incoming = consumer.receive(15000);
                        if (incoming instanceof TextMessage tm) {
                            targetReceived.complete(tm.getText());

                            // Reply to JMSReplyTo
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

        // Wait for consumer to be ready
        Thread.sleep(1000);

        // Step 2: ECHO.REQUEST queue that sends JMS message to Echo
        ActiveMQConnectionFactory echoFactory = new ActiveMQConnectionFactory(ECHO_ARTEMIS_URL);
        echoFactory.setUser("admin");
        echoFactory.setPassword("admin");

        String echoReply;
        try (Connection conn = echoFactory.createConnection()) {
            conn.start();
            try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue echoQueue = session.createQueue(ECHO_QUEUE);
                TemporaryQueue replyQueue = session.createTemporaryQueue();

                // Send to Echo
                MessageProducer producer = session.createProducer(echoQueue);
                TextMessage request = session.createTextMessage(requestBody);
                request.setJMSReplyTo(replyQueue);
                producer.send(request);

                // Waiting for a reply from Echo (Echo forwards to target → target replies → Echo replies)
                MessageConsumer replyConsumer = session.createConsumer(replyQueue);
                Message reply = replyConsumer.receive(20000);

                if (reply instanceof TextMessage tm) {
                    echoReply = tm.getText();
                } else {
                    echoReply = "NO_REPLY";
                }
            }
        }

        // Step 3: Verification
        // 3a. Target server did receive the message forwarded by Echo
        String receivedByTarget = targetReceived.get(10, TimeUnit.SECONDS);
        assertThat(receivedByTarget).isEqualTo(requestBody);

        // 3b. Client received the reply from target server (forwarded via Echo)
        assertThat(echoReply).isEqualTo(expectedReply);
    }
}
