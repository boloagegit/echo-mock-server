package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.entity.Protocol;
import com.echo.pipeline.JmsMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.PipelineResult;
import jakarta.jms.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * JMS 訊息監聯器 - 攔截或轉發到目標 JMS Server
 */
@Component
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@Slf4j
public class MockJmsListener {

    private final JmsConnectionManager connectionManager;
    private final JmsProperties jmsProperties;
    private final JmsMockPipeline jmsMockPipeline;
    private final JmsEndpointExtractor endpointExtractor;
    private final JmsMessageMemoryBudget memoryBudget;

    public MockJmsListener(JmsConnectionManager connectionManager,
                           JmsProperties jmsProperties,
                           JmsMockPipeline jmsMockPipeline,
                           JmsEndpointExtractor endpointExtractor,
                           JmsMessageMemoryBudget memoryBudget) {
        this.connectionManager = connectionManager;
        this.jmsProperties = jmsProperties;
        this.jmsMockPipeline = jmsMockPipeline;
        this.endpointExtractor = endpointExtractor;
        this.memoryBudget = memoryBudget;
    }

    @JmsListener(destination = "${echo.jms.queue:ECHO.REQUEST}")
    public void onMessage(Message message) {
        try {
            PipelineResult result = processMessage(message);

            // JMS 延遲同步執行
            if (result.getDelayMs() > 0) {
                Thread.sleep(result.getDelayMs());
            }

            // Check fault injection
            String faultType = result.getFaultType();
            if ("CONNECTION_RESET".equals(faultType)) {
                log.info("JMS fault injection: CONNECTION_RESET - skipping reply");
                return; // Don't send reply
            }
            if ("EMPTY_RESPONSE".equals(faultType)) {
                log.info("JMS fault injection: EMPTY_RESPONSE - sending empty reply");
                sendReply(message, "");
                return;
            }

            // 根據結果回覆 JMS 訊息
            if (result.getResponse() != null) {
                sendReply(message, result.getResponse().getBody());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("JMS processing interrupted before completion");
            throw new JmsProcessingInterruptedException(e);
        } catch (Exception e) {
            log.error("JMS processing error", e);
            sendErrorReply(message, e.getMessage());
        }
    }

    private PipelineResult processMessage(Message message) throws JMSException, InterruptedException {
        JmsMessageMemoryBudget.Reservation reservation = null;
        try {
            long encodedBodyBytes = encodedTextBodyBytes(message);
            if (encodedBodyBytes > 0) {
                // getBodySize() 是完整訊息大小；不使用只代表目前已下載部分的 buffer size。
                reservation = memoryBudget.reserveEncodedBody(encodedBodyBytes);
            }

            String body = extractBody(message);
            if (reservation == null) {
                reservation = memoryBudget.reserveText(body);
            }

            String queue = jmsProperties.getQueue();
            log.debug("JMS request received on queue: {}", queue);

            // endpoint 只掃描到目標欄位；規則條件由 pipeline 再做一次單趟串流比對，均不建立 DOM。
            String endpointValue = endpointExtractor.extract(body, jmsProperties.getEndpointField());
            String endpointLabel = (endpointValue != null && !endpointValue.isBlank())
                    ? queue + " | " + endpointValue : queue;

            MockRequest mockRequest = MockRequest.builder()
                    .protocol(Protocol.JMS)
                    .path(endpointLabel)
                    .body(body)
                    .clientIp("JMS")
                    .endpointValue(endpointValue)
                    .build();

            return jmsMockPipeline.execute(mockRequest);
        } finally {
            if (reservation != null) {
                reservation.close();
            }
        }
    }

    private String extractBody(Message message) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            return textMessage.getText();
        }
        return null;
    }

    private long encodedTextBodyBytes(Message message) {
        if (!(message instanceof TextMessage) || !(message instanceof ActiveMQMessage activeMQMessage)) {
            return -1;
        }
        var coreMessage = activeMQMessage.getCoreMessage();
        return coreMessage != null ? coreMessage.getBodySize() : -1;
    }

    private void sendReply(Message request, String responseBody) {
        try {
            Destination replyTo = request.getJMSReplyTo();
            if (replyTo == null) {
                return;
            }

            if (connectionManager.getJmsTemplate() == null) {
                return;
            }

            connectionManager.getJmsTemplate().send(replyTo, session -> {
                TextMessage reply = session.createTextMessage(responseBody);
                reply.setJMSCorrelationID(request.getJMSMessageID());
                return reply;
            });
        } catch (Exception e) {
            log.error("Failed to send reply: {}", e.getMessage());
        }
    }

    private void sendErrorReply(Message request, String error) {
        sendReply(request, "<error>" + error + "</error>");
    }

    private static final class JmsProcessingInterruptedException extends RuntimeException {
        private JmsProcessingInterruptedException(InterruptedException cause) {
            super("JMS processing interrupted", cause);
        }
    }
}
