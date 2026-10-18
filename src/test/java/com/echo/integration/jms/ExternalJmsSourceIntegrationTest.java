package com.echo.integration.jms;

import com.echo.dto.RuleDto;
import com.echo.dto.JmsTargetConnectionDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.jms.JmsConnectionManager;
import com.echo.jms.JmsTargetForwarder;
import com.echo.jms.JmsRuntimeMetrics;
import com.echo.repository.JmsTargetConnectionRepository;
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
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 從外部 broker（模擬公司 ESB）接收 JMS 請求：Echo 不啟動內建 Artemis，
 * 監聽外部 broker 上的 queue，並把回覆送到訊息的 JMSReplyTo。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
        registry.add("echo.jms.concurrency", () -> "2-2");
    }

    @AfterAll
    static void stopEsb(@Autowired JmsListenerEndpointRegistry listeners) throws Exception {
        listeners.stop();
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
    @DisplayName("改寫已快取的共用回應後，真實 JMS 與 HTTP 下一筆請求都取得新內容")
    void rewritingAWarmResponseUpdatesRealJmsAndHttpReplies() throws Exception {
        RuleDto rule = createJmsRule(QUEUE, "before");
        RuleDto shared = RuleDto.builder().protocol(Protocol.HTTP).method("GET")
                .matchKey("/external-jms/shared-response").status(200).responseId(rule.getResponseId()).build();
        assertThat(adminClient().postForEntity("/api/admin/rules", shared, RuleDto.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        waitUntilReceiving();
        assertThat(request("<req/>").body()).isEqualTo("before");
        assertThat(restTemplate.getForObject("/mock/external-jms/shared-response", String.class)).isEqualTo("before");

        var saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(Map.of("protocol", "JMS", "matchKey", QUEUE,
                        "version", rule.getVersion(), "responseId", rule.getResponseId(),
                        "responseBody", "after", "updateExistingResponse", true)), RuleDto.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).isNotNull();
        assertThat(saved.getBody().getResponseId()).isEqualTo(rule.getResponseId());
        Reply reply = request("<req/>");
        assertThat(reply.body()).isEqualTo("after");
        assertThat(reply.correlationId()).isEqualTo(reply.requestId());
        assertThat(restTemplate.getForObject("/mock/external-jms/shared-response", String.class)).isEqualTo("after");
    }

    @Test
    @DisplayName("ESB 重啟後 Echo 自動重新連線並繼續處理")
    void reconnectsAfterEsbRestart() throws Exception {
        createJmsRule(QUEUE, "<reply>again</reply>");
        waitUntilReceiving();
        assertThat(request("<req/>").body()).isEqualTo("<reply>again</reply>");

        esb.stop();
        try {
            waitUntil(() -> jmsConnectionManager.listenerState() == JmsConnectionManager.ListenerState.RECONNECTING, 10_000);
            assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode().value())
                    .isEqualTo(503);
            assertThat(restTemplate.getForEntity("/actuator/health/liveness", String.class).getStatusCode().value())
                    .isEqualTo(200);
        } finally {
            esb = startEsb();
        }
        waitUntilReceiving();

        assertThat(request("<req/>").body()).isEqualTo("<reply>again</reply>");
        assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("外部 ESB 模式也套用共用模板，可取得 correlation ID 與自訂屬性")
    void templateUsesCorrelationIdAndPropertiesFromEsbMessage() throws Exception {
        createJmsRule(QUEUE, "<reply><corr>{{request.headers.JMSCorrelationID}}</corr>"
                + "<branch>{{request.headers.branch}}</branch><amount>{{jsonPath request.body '$.amount'}}</amount></reply>");
        waitUntilReceiving();

        Reply reply = request("{\"amount\":42}", message -> {
            message.setJMSCorrelationID("TXN-7");
            message.setStringProperty("branch", "TPE");
        });

        assertThat(reply.body()).isEqualTo("<reply><corr>TXN-7</corr><branch>TPE</branch><amount>42</amount></reply>");
    }

    @Test
    @DisplayName("一個慢 JMS 目標等待回覆時，另一目標與 HTTP 仍可處理")
    void oneSlowTargetLeavesCapacityForHealthyJmsAndHttp() throws Exception {
        exerciseSlowTarget(1);
    }

    @Test
    @DisplayName("同一來源的兩個 listener 都等待慢目標時，JMS 排隊並在目標恢復後正確完成")
    void saturatedSharedListenersRetainAndRecoverQueuedRequests() throws Exception {
        exerciseSlowTarget(2);
    }

    private void exerciseSlowTarget(int blockedRequests) throws Exception {
        var senders = Executors.newFixedThreadPool(blockedRequests + 1);
        var release = new CountDownLatch(1);
        var received = new CountDownLatch(blockedRequests);
        var failure = new AtomicReference<Throwable>();
        var targetIds = new ArrayList<String>();
        try (var factory = new ActiveMQConnectionFactory(esbUrl());
             var downstream = factory.createConnection()) {
            downstream.start();
            var sessions = new ArrayList<Session>();
            try {
                // Each JMS session has exactly one callback thread, including its reply producer.
                for (int i = 0; i < blockedRequests + 1; i++) {
                    boolean slow = i < blockedRequests;
                    var session = downstream.createSession(false, Session.AUTO_ACKNOWLEDGE);
                    sessions.add(session);
                    var consumer = session.createConsumer(session.createQueue(slow ? "TEST.SLOW" : "TEST.HEALTHY"));
                    var producer = session.createProducer(null);
                    consumer.setMessageListener(message -> {
                        try {
                            if (slow) {
                                received.countDown();
                                if (!release.await(15, TimeUnit.SECONDS)) {
                                    throw new AssertionError("test did not release the slow target");
                                }
                            }
                            TextMessage reply = session.createTextMessage(slow ? "slow-reply" : "healthy-reply");
                            reply.setJMSCorrelationID(message.getJMSMessageID());
                            producer.send(message.getJMSReplyTo(), reply);
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        }
                    });
                }
                for (String mode : List.of("slow", "healthy")) {
                    var response = adminClient().postForEntity("/api/admin/jms-target-connections", Map.of(
                            "name", "isolation-" + mode, "providerType", "artemis", "serverUrl", esbUrl(),
                            "queueName", "TEST." + mode.toUpperCase(java.util.Locale.ROOT),
                            "timeoutSeconds", 10, "enabled", true), JmsTargetConnectionDto.class);
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                    assertThat(response.getBody()).isNotNull();
                    String id = response.getBody().id();
                    targetIds.add(id);
                    var rule = adminClient().postForEntity("/api/admin/rules", Map.of(
                            "protocol", "JMS", "matchKey", QUEUE, "bodyCondition", "//ServiceName=" + mode,
                            "action", "FORWARD", "forwardTargetMode", "CONNECTION", "jmsTargetConnectionId", id),
                            RuleDto.class);
                    assertThat(rule.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                }
                createHttpRule("/jms-isolation/http", "GET", "http-healthy");
                waitUntilReceiving();
                var slowReplies = new ArrayList<java.util.concurrent.Future<Reply>>();
                for (int i = 0; i < blockedRequests; i++) {
                    slowReplies.add(senders.submit(() -> request("<Request><ServiceName>slow</ServiceName></Request>")));
                }
                assertThat(received.await(10, TimeUnit.SECONDS)).as("all slow forwards are waiting").isTrue();
                var healthy = senders.submit(() -> request("<Request><ServiceName>healthy</ServiceName></Request>"));
                long httpStart = System.nanoTime();
                assertThat(restTemplate.getForObject("/mock/jms-isolation/http", String.class)).isEqualTo("http-healthy");
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - httpStart)).isLessThan(2_000);
                if (blockedRequests == 2) {
                    // This is the existing shared-pool boundary, not a provider/map-lock failure.
                    assertThatThrownBy(() -> healthy.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } else {
                    assertReply(healthy.get(5, TimeUnit.SECONDS), "healthy-reply");
                }
                release.countDown();
                for (var reply : slowReplies) assertReply(reply.get(10, TimeUnit.SECONDS), "slow-reply");
                assertReply(healthy.get(10, TimeUnit.SECONDS), "healthy-reply");
                assertThat(failure.get()).isNull();
                waitUntil(() -> ((Number) context.getBean(JmsRuntimeMetrics.class).snapshot().get("listenerActive"))
                        .intValue() == 0, 5_000);
                assertThat(esb.getActiveMQServer().locateQueue(QUEUE).getMessageCount()).isZero();
            } finally {
                release.countDown();
                senders.shutdown();
                if (!senders.awaitTermination(15, TimeUnit.SECONDS)) senders.shutdownNow();
                for (Session session : sessions) session.close();
            }
        } finally {
            release.countDown();
            senders.shutdownNow();
            adminClient().delete("/api/admin/rules/all");
            for (String id : targetIds) context.getBean(JmsTargetForwarder.class).evict(Long.valueOf(id));
            context.getBean(JmsTargetConnectionRepository.class).deleteAll();
        }
    }

    private static void assertReply(Reply reply, String expected) {
        assertThat(reply.body()).isEqualTo(expected);
        assertThat(reply.correlationId()).isEqualTo(reply.requestId());
    }

    private record Reply(String requestId, String correlationId, String body) {
    }

    private interface MessageCustomizer {
        void customize(TextMessage message) throws Exception;
    }

    /** 模擬公司系統：送一則帶 JMSReplyTo 的請求到 ESB，等回覆。 */
    private static Reply request(String body) throws Exception {
        return request(body, message -> { });
    }

    private static Reply request(String body, MessageCustomizer customizer) throws Exception {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(esbUrl());
        try (factory; Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(QUEUE);
            TemporaryQueue replyTo = session.createTemporaryQueue();
            MessageProducer producer = session.createProducer(queue);
            TextMessage message = session.createTextMessage(body);
            message.setJMSReplyTo(replyTo);
            customizer.customize(message);
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
