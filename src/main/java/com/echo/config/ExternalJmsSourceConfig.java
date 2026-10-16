package com.echo.config;

import com.echo.jms.target.JmsTargetFactoryProvider;
import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.connection.CachingConnectionFactory;

import java.util.List;

/**
 * 從外部 broker（例如公司 ESB 上的 TIBCO EMS 或 Artemis）接收 JMS 請求。
 *
 * <p>多台 Echo 監聽 broker 上同一個 queue（{@code echo.jms.queue}）時，broker 會把每則訊息只交給其中一台，
 * 達到分擔量與備援；回覆送到訊息的 JMSReplyTo，也在同一個 broker 上。
 * 此時不啟動內建 Artemis，也不開啟 {@code echo.jms.port}。</p>
 *
 * <p>連線與內建模式一樣包一層 {@link CachingConnectionFactory}（Spring Boot 內建模式的預設），
 * 讓回覆與測試發送重用同一條連線；連線中斷時由它與 listener container 重新建立。</p>
 */
@Configuration
@ConditionalOnProperty(name = "echo.jms.enabled", havingValue = "true")
@ConditionalOnExpression("T(com.echo.config.JmsProperties$Source).isExternalType('${echo.jms.source.type:embedded}')")
@Slf4j
public class ExternalJmsSourceConfig {

    @Bean
    public ConnectionFactory jmsConnectionFactory(JmsProperties properties,
                                                  List<JmsTargetFactoryProvider> providers) throws Exception {
        JmsProperties.Source source = properties.getSource();
        String type = source.getType().trim();
        if (source.getServerUrl() == null || source.getServerUrl().isBlank()) {
            throw new IllegalStateException("echo.jms.source.server-url is required when echo.jms.source.type is "
                    + type);
        }
        if (source.getReconnectIntervalMs() < 100) {
            throw new IllegalStateException("echo.jms.source.reconnect-interval-ms must be at least 100");
        }
        JmsTargetFactoryProvider provider = providers.stream()
                .filter(candidate -> candidate.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unsupported echo.jms.source.type: " + type
                        + " (use embedded, artemis or tibco)"));
        ConnectionFactory target = provider.create(source.asConnectionTarget());
        CachingConnectionFactory caching = new CachingConnectionFactory(target);
        caching.setReconnectOnException(true);
        log.info("JMS requests are received from external {} broker {} queue {}; embedded Artemis is not started",
                type, source.getServerUrl(), properties.getQueue());
        return caching;
    }
}
