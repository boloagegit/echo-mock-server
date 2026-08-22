package com.echo.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisMode;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Artemis 配置 - 從 echo.jms 讀取設定
 * <p>
 * Embedded mode 同時啟用 in-VM transport 和 TCP acceptor。
 * App 內部的 JmsListener 和 JmsTemplate 走 in-VM，
 * 外部服務可透過 TCP (預設 tcp://localhost:61616) 連入發送訊息。
 * <p>
 * 注意：使用 fully qualified @Configuration 避免與 Artemis Configuration 類別衝突
 */
@org.springframework.context.annotation.Configuration
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@Slf4j
public class ArtemisConfig {

    @Bean
    @Primary
    public ArtemisProperties artemisProperties(JmsProperties jmsProperties) {
        ArtemisProperties props = new ArtemisProperties();
        props.setMode(ArtemisMode.EMBEDDED);
        props.setUser(jmsProperties.getUsername());
        props.setPassword(jmsProperties.getPassword());
        props.getEmbedded().setEnabled(true);
        // 關閉 persistence 時 Artemis 會用 heap 組裝 large message，paging 尚未介入就可能 OOM。
        props.getEmbedded().setPersistent(jmsProperties.isPersistent());
        props.getEmbedded().setDataDirectory(jmsProperties.getDataDirectory());
        return props;
    }

    /**
     * 加入 TCP acceptor，讓外部服務可透過 tcp://localhost:{port} 連入
     */
    @Bean
    public ArtemisConfigurationCustomizer artemisConfigurationCustomizer(JmsProperties jmsProperties) {
        return configuration -> {
            try {
                int port = jmsProperties.getPort();
                String acceptorUrl = "tcp://0.0.0.0:" + port
                        + "?protocols=CORE,OPENWIRE,AMQP"
                        + "&anycastPrefix=jms.queue."
                        + "&multicastPrefix=jms.topic.";
                configuration.addAcceptorConfiguration("tcp", acceptorUrl);

                int memoryPercent = jmsProperties.getBrokerMemoryPercent();
                if (memoryPercent <= 0 || memoryPercent > 50) {
                    throw new IllegalArgumentException("JMS broker memory percent must be between 1 and 50");
                }
                long maxHeapBytes = Runtime.getRuntime().maxMemory();
                long brokerMaxBytes = maxHeapBytes / 100 * memoryPercent
                        + maxHeapBytes % 100 * memoryPercent / 100;
                configuration.setGlobalMaxSize(Math.max(1, brokerMaxBytes));
                // Echo 是 transient mock broker；large message 仍落盤保護 heap，但不為每筆做 fsync。
                configuration.setLargeMessageSync(false);
                configuration.addAddressSetting("#", new AddressSettings()
                        .setAddressFullMessagePolicy(AddressFullMessagePolicy.PAGE));

                log.info("Artemis TCP acceptor configured on port {}, paging after {} MB",
                        port, brokerMaxBytes / (1024 * 1024));
            } catch (Exception e) {
                log.error("Failed to configure Artemis TCP acceptor: {}", e.getMessage(), e);
            }
        };
    }
}
