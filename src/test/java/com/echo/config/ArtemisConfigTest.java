package com.echo.config;

import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
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
        jmsProperties.setPersistent(false);

        ArtemisConfig config = new ArtemisConfig();
        ArtemisProperties props = config.artemisProperties(jmsProperties);

        assertThat(props.getMode()).isEqualTo(ArtemisMode.EMBEDDED);
        assertThat(props.getBrokerUrl()).isNull();
        assertThat(props.getUser()).isEqualTo("testuser");
        assertThat(props.getPassword()).isEqualTo("testpass");
        assertThat(props.getEmbedded().isEnabled()).isTrue();
        assertThat(props.getEmbedded().isPersistent()).isFalse();
        assertThat(props.getEmbedded().getDataDirectory()).isEqualTo("./data/artemis");
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
        jmsProperties.setBrokerMemoryPercent(10);

        ArtemisConfig config = new ArtemisConfig();
        ArtemisConfigurationCustomizer customizer = config.artemisConfigurationCustomizer(jmsProperties);

        ConfigurationImpl artemisConfiguration = new ConfigurationImpl();
        customizer.customize(artemisConfiguration);

        assertThat(artemisConfiguration.getAcceptorConfigurations()).hasSize(1);
        assertThat(artemisConfiguration.getAcceptorConfigurations().iterator().next().getParams())
                .containsEntry("port", "61617");
        assertThat(artemisConfiguration.getGlobalMaxSize())
                .isEqualTo(Runtime.getRuntime().maxMemory() / 10);
        assertThat(artemisConfiguration.getAddressSettings().get("#").getAddressFullMessagePolicy())
                .isEqualTo(AddressFullMessagePolicy.PAGE);
        assertThat(artemisConfiguration.isLargeMessageSync()).isTrue();
        assertThat(artemisConfiguration.getAddressSettings().get("#").getRedeliveryDelay())
                .isEqualTo(250);
        assertThat(artemisConfiguration.getAddressSettings().get("#").getRedeliveryMultiplier())
                .isEqualTo(2.0);
        assertThat(artemisConfiguration.getAddressSettings().get("#").getMaxRedeliveryDelay())
                .isEqualTo(5_000);
        assertThat(artemisConfiguration.getAddressSettings().get("#").getMaxDeliveryAttempts())
                .isEqualTo(-1);
        assertThat(artemisConfiguration.getMinDiskFree()).isEqualTo(512L * 1024 * 1024);
        assertThat(artemisConfiguration.getDiskScanPeriod()).isEqualTo(1_000);
    }

    @Test
    void artemisConfigurationCustomizer_shouldDeleteIdleTemporaryQueuesByDefault() throws Exception {
        ConfigurationImpl artemisConfiguration = new ConfigurationImpl();
        new ArtemisConfig().artemisConfigurationCustomizer(new JmsProperties()).customize(artemisConfiguration);

        assertThat(artemisConfiguration.getTemporaryQueueNamespace())
                .isEqualTo(ArtemisConfig.TEMPORARY_QUEUE_NAMESPACE);
        var temporary = artemisConfiguration.getAddressSettings()
                .get(ArtemisConfig.TEMPORARY_QUEUE_NAMESPACE + ".#");
        assertThat(temporary.isAutoDeleteCreatedQueues()).isTrue();
        assertThat(temporary.getAutoDeleteQueuesDelay()).isEqualTo(10_000);
        assertThat(artemisConfiguration.getAddressQueueScanPeriod())
                .isEqualTo(ArtemisConfig.TEMPORARY_QUEUE_SCAN_PERIOD_MS);
        assertThat(temporary.getAutoDeleteQueuesMessageCount()).isEqualTo(-1);
        assertThat(artemisConfiguration.getAddressSettings().get("#").isAutoDeleteCreatedQueues()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void artemisConfigurationCustomizer_zeroKeepsTemporaryQueuesUntilDisconnect() throws Exception {
        JmsProperties jmsProperties = new JmsProperties();
        jmsProperties.setTemporaryQueueIdleDeleteMs(0);
        ConfigurationImpl artemisConfiguration = new ConfigurationImpl();
        new ArtemisConfig().artemisConfigurationCustomizer(jmsProperties).customize(artemisConfiguration);

        assertThat(artemisConfiguration.getTemporaryQueueNamespace()).isNotEqualTo(ArtemisConfig.TEMPORARY_QUEUE_NAMESPACE);
        assertThat(artemisConfiguration.getAddressSettings())
                .doesNotContainKey(ArtemisConfig.TEMPORARY_QUEUE_NAMESPACE + ".#");
    }

    @Test
    void artemisConfigurationCustomizer_rejectsNegativeTemporaryQueueIdleTime() {
        JmsProperties jmsProperties = new JmsProperties();
        jmsProperties.setTemporaryQueueIdleDeleteMs(-1);
        ArtemisConfigurationCustomizer customizer = new ArtemisConfig().artemisConfigurationCustomizer(jmsProperties);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> customizer.customize(new ConfigurationImpl()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temporary queue");
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
