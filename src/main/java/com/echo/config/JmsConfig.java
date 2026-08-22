package com.echo.config;

import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.SingleConnectionFactory;

/**
 * JMS 配置 - 只在 echo.jms.enabled=true 時啟用
 */
@Configuration
@EnableJms
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
public class JmsConfig {

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            ConnectionFactory connectionFactory,
            JmsProperties jmsProperties,
            @Value("${echo.jms.concurrency:1-5}") String concurrency) {
        ConnectionFactory target = connectionFactory;
        if (connectionFactory instanceof SingleConnectionFactory singleConnectionFactory) {
            target = singleConnectionFactory.getTargetConnectionFactory();
        }
        if (target instanceof ActiveMQConnectionFactory activeMQConnectionFactory) {
            activeMQConnectionFactory.setConsumerWindowSize(jmsProperties.getConsumerWindowSize());
        }
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(concurrency);
        return factory;
    }
}
