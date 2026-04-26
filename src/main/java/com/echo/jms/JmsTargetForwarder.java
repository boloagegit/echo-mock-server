package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JMS 轉發器 - 轉發訊息到目標 JMS Server。
 * 透過 {@link JmsTargetFactoryProvider} 策略模式支援多種 JMS provider。
 */
@Component
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@Slf4j
public class JmsTargetForwarder {

    private final JmsProperties jmsProperties;
    private final List<JmsTargetFactoryProvider> factoryProviders;
    private volatile ConnectionFactory targetFactory;
    private volatile Connection targetConnection;

    public JmsTargetForwarder(JmsProperties jmsProperties, List<JmsTargetFactoryProvider> factoryProviders) {
        this.jmsProperties = jmsProperties;
        this.factoryProviders = factoryProviders;
    }

    @jakarta.annotation.PreDestroy
    public void cleanup() {
        if (targetConnection != null) {
            try {
                targetConnection.close();
            } catch (Exception e) {
                log.debug("Error closing target JMS connection: {}", e.getMessage());
            }
        }
    }

    /**
     * 轉發訊息到目標 JMS Server，等待回應
     * 使用 target.queue 作為目標 Queue（非 source queue）
     */
    public String forward(String body, Message originalMessage) {
        JmsProperties.Target target = jmsProperties.getTarget();
        String targetQueue = target.getQueue();
        int timeoutMs = target.getTimeoutSeconds() * 1000;

        try {
            ConnectionFactory factory = getOrCreateFactory();
            
            try (Session session = getConnection(factory).createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue destQueue = session.createQueue(targetQueue);
                TemporaryQueue replyQueue = session.createTemporaryQueue();

                // 發送訊息
                MessageProducer producer = session.createProducer(destQueue);
                TextMessage forwardMsg = session.createTextMessage(body);
                forwardMsg.setJMSReplyTo(replyQueue);
                
                try {
                    forwardMsg.setJMSCorrelationID(originalMessage.getJMSMessageID());
                } catch (Exception e) {
                    log.debug("Failed to set JMSCorrelationID: {}", e.getMessage());
                }
                
                producer.send(forwardMsg);
                log.debug("Forwarded message to target queue: {}", targetQueue);

                // 等待回應
                MessageConsumer consumer = session.createConsumer(replyQueue);
                Message response = consumer.receive(timeoutMs);

                if (response instanceof TextMessage textMessage) {
                    String responseBody = textMessage.getText();
                    log.debug("Received response from target JMS");
                    return responseBody;
                } else {
                    log.warn("Target JMS response timeout or invalid type");
                    return "<error>JMS response timeout</error>";
                }
            }

        } catch (JMSException e) {
            resetConnection();
            log.error("Failed to forward to target JMS (connection reset): {}", e.getMessage());
            return "<error>JMS forward error: " + e.getMessage() + "</error>";
        } catch (Exception e) {
            log.error("Failed to forward to target JMS: {}", e.getMessage());
            return "<error>JMS forward error: " + e.getMessage() + "</error>";
        }
    }

    private void resetConnection() {
        synchronized (this) {
            if (targetConnection != null) {
                try {
                    targetConnection.close();
                } catch (Exception e) {
                    log.debug("Error closing target JMS connection during reset: {}", e.getMessage());
                }
                targetConnection = null;
                log.info("Target JMS connection reset, will reconnect on next forward");
            }
        }
    }

    private ConnectionFactory getOrCreateFactory() throws Exception {
        if (targetFactory == null) {
            synchronized (this) {
                if (targetFactory == null) {
                    targetFactory = createFactory();
                }
            }
        }
        return targetFactory;
    }

    private Connection getConnection(ConnectionFactory factory) throws JMSException {
        if (targetConnection == null) {
            synchronized (this) {
                if (targetConnection == null) {
                    Connection conn = factory.createConnection();
                    conn.start();
                    targetConnection = conn;
                }
            }
        }
        return targetConnection;
    }

    private ConnectionFactory createFactory() throws Exception {
        String type = jmsProperties.getTarget().getType();
        return factoryProviders.stream()
                .filter(p -> p.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unsupported JMS target type: " + type +
                        ". Supported: " + factoryProviders.stream()
                                .map(p -> p.getClass().getSimpleName())
                                .toList()))
                .create(jmsProperties.getTarget());
    }
}
