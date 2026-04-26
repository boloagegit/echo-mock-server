package com.echo.jms;

import com.echo.config.JmsProperties;
import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class JmsConnectionManagerTest {

    @Mock
    private ConnectionFactory connectionFactory;

    private JmsConnectionManager manager;
    private JmsProperties jmsProperties;

    @BeforeEach
    void setUp() {
        jmsProperties = new JmsProperties();
        jmsProperties.setEnabled(true);
        jmsProperties.setAlias("ESB");
        jmsProperties.setPort(61616);
        jmsProperties.setQueue("ECHO.REQUEST");
        
        manager = new JmsConnectionManager(jmsProperties, connectionFactory);
    }

    @Test
    void init_shouldCreateJmsTemplate() {
        manager.init();
        assertThat(manager.getJmsTemplate()).isNotNull();
        assertThat(manager.isConnected()).isTrue();
    }

    @Test
    void getStatus_shouldReturnCorrectStatus() {
        manager.init();
        var status = manager.getStatus();

        assertThat(status.isEnabled()).isTrue();
        assertThat(status.isConnected()).isTrue();
        assertThat(status.getBrokerUrl()).isEqualTo("tcp://localhost:61616");
        assertThat(status.getQueue()).isEqualTo("ECHO.REQUEST");
        assertThat(status.getDisplayName()).isEqualTo("ESB");
    }

    @Test
    void isConnected_shouldReturnFalse_beforeInit() {
        assertThat(manager.isConnected()).isFalse();
    }

    @Test
    void jmsStatus_shouldBuildCorrectly() {
        var status = JmsConnectionManager.JmsStatus.builder()
                .enabled(true)
                .connected(true)
                .brokerUrl("tcp://localhost:61616")
                .queue("ORDER.QUEUE")
                .displayName("ESB")
                .targetEnabled(true)
                .targetType("tibco")
                .targetUrl("tcp://esb:7222")
                .build();

        assertThat(status.isEnabled()).isTrue();
        assertThat(status.isConnected()).isTrue();
        assertThat(status.getBrokerUrl()).isEqualTo("tcp://localhost:61616");
        assertThat(status.getQueue()).isEqualTo("ORDER.QUEUE");
        assertThat(status.isTargetEnabled()).isTrue();
        assertThat(status.getTargetType()).isEqualTo("tibco");
        assertThat(status.getTargetUrl()).isEqualTo("tcp://esb:7222");
    }
}
