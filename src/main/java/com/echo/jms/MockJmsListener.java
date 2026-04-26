package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.entity.Protocol;
import com.echo.pipeline.JmsMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.PipelineResult;
import com.echo.service.ConditionMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.jms.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathFactory;

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
    private final ConditionMatcher conditionMatcher;

    public MockJmsListener(JmsConnectionManager connectionManager,
                           JmsProperties jmsProperties,
                           JmsMockPipeline jmsMockPipeline,
                           ConditionMatcher conditionMatcher) {
        this.connectionManager = connectionManager;
        this.jmsProperties = jmsProperties;
        this.jmsMockPipeline = jmsMockPipeline;
        this.conditionMatcher = conditionMatcher;
    }

    @JmsListener(destination = "${echo.jms.queue:ECHO.REQUEST}")
    public void onMessage(Message message) {
        try {
            String body = extractBody(message);
            String queue = jmsProperties.getQueue();

            log.info("JMS request received on queue: {}", queue);

            // 一次性 parse body，後續 endpoint 提取與規則匹配共用
            ConditionMatcher.PreparedBody prepared = conditionMatcher.prepareBody(body);

            // 從已解析的 PreparedBody 提取 endpoint-field 值（不再重複 parse）
            String endpointValue = extractEndpointValue(prepared);
            String endpointLabel = (endpointValue != null && !endpointValue.isBlank())
                    ? queue + " | " + endpointValue : queue;

            // 建構 MockRequest
            MockRequest mockRequest = MockRequest.builder()
                    .protocol(Protocol.JMS)
                    .path(endpointLabel)
                    .body(body)
                    .clientIp("JMS")
                    .endpointValue(endpointValue)
                    .preparedBody(prepared)
                    .build();

            // 委派給 pipeline 執行
            PipelineResult result = jmsMockPipeline.execute(mockRequest);

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

        } catch (Exception e) {
            log.error("JMS processing error", e);
            sendErrorReply(message, e.getMessage());
        }
    }

    private String extractBody(Message message) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            return textMessage.getText();
        }
        return null;
    }

    /**
     * 從已解析的 PreparedBody 提取 endpoint-field 的值（複用已 parse 的 DOM/JSON，不再重複解析）
     * @return 欄位值，或 null
     */
    private String extractEndpointValue(ConditionMatcher.PreparedBody prepared) {
        String field = jmsProperties.getEndpointField();
        if (field == null || field.isBlank()) {
            return null;
        }
        try {
            Document xmlDoc = prepared.getXmlDoc();
            if (xmlDoc != null) {
                var xpath = XPathFactory.newInstance().newXPath();
                String value = xpath.evaluate("//" + field + "/text()", xmlDoc);
                return (value != null && !value.isBlank()) ? value : null;
            }
            JsonNode jsonNode = prepared.getJsonNode();
            if (jsonNode != null) {
                var target = jsonNode.get(field);
                if (target != null && !target.isNull()) {
                    String value = target.asText();
                    return (value != null && !value.isBlank()) ? value : null;
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to extract endpoint field '{}' from prepared body: {}", field, e.getMessage());
            return null;
        }
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
}
