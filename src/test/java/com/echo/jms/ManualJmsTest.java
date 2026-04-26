package com.echo.jms;

import jakarta.jms.*;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Manual test — requires external Artemis broker on localhost:61616")
public class ManualJmsTest {
    @Test
    void sendAndReceive() throws Exception {
        String brokerUrl = "tcp://localhost:61616";
        
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl)) {
            factory.setUser("admin");
            factory.setPassword("admin");
            
            try (Connection connection = factory.createConnection();
                 Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                
                connection.start();
                Queue requestQueue = session.createQueue("ECHO.REQUEST");
                TemporaryQueue replyQueue = session.createTemporaryQueue();
                
                MessageProducer producer = session.createProducer(requestQueue);
                MessageConsumer consumer = session.createConsumer(replyQueue);
                
                // 無匹配規則的請求
                String xml = "<request><serviceName>UnknownService</serviceName></request>";
                System.out.println("--- 無匹配規則測試 ---");
                System.out.println("Sent: " + xml);
                
                TextMessage request = session.createTextMessage(xml);
                request.setJMSReplyTo(replyQueue);
                request.setJMSCorrelationID("test-" + System.currentTimeMillis());
                producer.send(request);
                
                Message response = consumer.receive(5000);
                if (response instanceof TextMessage textMessage) {
                    System.out.println("Received: " + textMessage.getText());
                } else {
                    System.out.println("No response");
                }
            }
        }
    }
}
