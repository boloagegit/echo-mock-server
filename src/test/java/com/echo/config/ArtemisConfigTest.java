package com.echo.config;

import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisMode;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;

import static org.assertj.core.api.Assertions.assertThat;

class ArtemisConfigTest {

    @Test
    void artemisProperties_shouldUseJmsPropertiesValues() {
        JmsProperties jmsProperties = new JmsProperties();
        jmsProperties.setPort(61617);
        jmsProperties.setUsername("testuser");
        jmsProperties.setPassword("testpass");

        ArtemisConfig config = new ArtemisConfig();
        ArtemisProperties props = config.artemisProperties(jmsProperties);

        assertThat(props.getMode()).isEqualTo(ArtemisMode.EMBEDDED);
        assertThat(props.getBrokerUrl()).isNull();
        assertThat(props.getUser()).isEqualTo("testuser");
        assertThat(props.getPassword()).isEqualTo("testpass");
        assertThat(props.getEmbedded().isEnabled()).isTrue();
    }

    @Test
    void artemisProperties_shouldNotSetBrokerUrl() {
        JmsProperties jmsProperties = new JmsProperties();

        ArtemisConfig config = new ArtemisConfig();
        ArtemisProperties props = config.artemisProperties(jmsProperties);

        assertThat(props.getBrokerUrl()).isNull();
    }

    @Test
    void artemisConfigurationCustomizer_shouldAddTcpAcceptor() throws Exception {
        JmsProperties jmsProperties = new JmsProperties();
        jmsProperties.setPort(61617);

        ArtemisConfig config = new ArtemisConfig();
        ArtemisConfigurationCustomizer customizer = config.artemisConfigurationCustomizer(jmsProperties);

        ConfigurationImpl artemisConfiguration = new ConfigurationImpl();
        customizer.customize(artemisConfiguration);

        assertThat(artemisConfiguration.getAcceptorConfigurations()).hasSize(1);
        assertThat(artemisConfiguration.getAcceptorConfigurations().iterator().next().getParams())
                .containsEntry("port", "61617");
    }

    @Test
    void artemisConfigurationCustomizer_shouldUseDefaultPort() throws Exception {
        JmsProperties jmsProperties = new JmsProperties();
        // 預設 port = 61616

        ArtemisConfig config = new ArtemisConfig();
        ArtemisConfigurationCustomizer customizer = config.artemisConfigurationCustomizer(jmsProperties);

        ConfigurationImpl artemisConfiguration = new ConfigurationImpl();
        customizer.customize(artemisConfiguration);

        assertThat(artemisConfiguration.getAcceptorConfigurations()).hasSize(1);
        assertThat(artemisConfiguration.getAcceptorConfigurations().iterator().next().getParams())
                .containsEntry("port", "61616");
    }
}
