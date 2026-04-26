package com.echo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JmsPropertiesTest {

    @Test
    void shouldHaveCorrectDefaults() {
        JmsProperties props = new JmsProperties();

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getAlias()).isEqualTo("JMS");
        assertThat(props.getPort()).isEqualTo(61616);
        assertThat(props.getUsername()).isEqualTo("admin");
        assertThat(props.getPassword()).isEqualTo("admin");
        assertThat(props.getQueue()).isEqualTo("ECHO.REQUEST");
    }

    @Test
    void targetShouldHaveCorrectDefaults() {
        JmsProperties props = new JmsProperties();
        JmsProperties.Target target = props.getTarget();

        assertThat(target.isEnabled()).isFalse();
        assertThat(target.getType()).isEqualTo("tibco");
        assertThat(target.getServerUrl()).isNull();
        assertThat(target.getUsername()).isNull();
        assertThat(target.getPassword()).isNull();
        assertThat(target.getTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    void shouldAllowSettingAllProperties() {
        JmsProperties props = new JmsProperties();
        props.setEnabled(true);
        props.setAlias("ESB");
        props.setPort(61617);
        props.setUsername("user1");
        props.setPassword("pass1");
        props.setQueue("ORDER.QUEUE");

        props.getTarget().setEnabled(true);
        props.getTarget().setType("artemis");
        props.getTarget().setServerUrl("tcp://esb:7222");
        props.getTarget().setUsername("jmsuser");
        props.getTarget().setPassword("jmspass");
        props.getTarget().setTimeoutSeconds(60);

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getAlias()).isEqualTo("ESB");
        assertThat(props.getPort()).isEqualTo(61617);
        assertThat(props.getQueue()).isEqualTo("ORDER.QUEUE");

        assertThat(props.getTarget().isEnabled()).isTrue();
        assertThat(props.getTarget().getType()).isEqualTo("artemis");
        assertThat(props.getTarget().getServerUrl()).isEqualTo("tcp://esb:7222");
        assertThat(props.getTarget().getTimeoutSeconds()).isEqualTo(60);
    }
}
