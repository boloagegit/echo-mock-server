package com.echo.jms.target;

import com.echo.config.JmsProperties;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundJmsTimeoutPolicyTest {
    @Test
    void outboundArtemisCallsConnectsAndFailoverWaitsAreFiniteWithoutChangingTheSourceFactory() {
        var target = new JmsProperties.Target();
        target.setType("artemis");
        target.setTimeoutSeconds(1);
        target.setServerUrl("tcp://127.0.0.1:61616?callTimeout=9000&connect-timeout-millis=8000&reconnectAttempts=4");
        var provider = new ArtemisFactoryProvider();
        try (var source = (ActiveMQConnectionFactory) provider.create(target);
             var outbound = (ActiveMQConnectionFactory) provider.createOutbound(target)) {
            assertThat(source.getCallTimeout()).isEqualTo(9000);
            assertThat(source.getReconnectAttempts()).isEqualTo(4);
            assertThat(outbound.getCallTimeout()).isEqualTo(1000);
            assertThat(outbound.getCallFailoverTimeout()).isEqualTo(1000);
            assertThat(outbound.getInitialConnectAttempts()).isZero();
            assertThat(outbound.getReconnectAttempts()).isZero();
            assertThat(outbound.getServerLocator().getStaticTransportConfigurations()[0].getParams())
                    .containsEntry(TransportConstants.NETTY_CONNECT_TIMEOUT, 1000);
        }
    }

    @Test
    void aStricterTransportTimeoutIsPreserved() {
        var target = new JmsProperties.Target();
        target.setTimeoutSeconds(5);
        target.setServerUrl("tcp://127.0.0.1:61616?callTimeout=250&connect-timeout-millis=200");
        try (var factory = (ActiveMQConnectionFactory) new ArtemisFactoryProvider().createOutbound(target)) {
            assertThat(factory.getCallTimeout()).isEqualTo(250);
            assertThat(factory.getServerLocator().getStaticTransportConfigurations()[0].getParams())
                    .containsEntry(TransportConstants.NETTY_CONNECT_TIMEOUT, 200);
        }
    }

    @Test
    void emsUsesPerFactoryAttemptLimitsAndRejectsClientsWithoutTheRequiredApi() throws Exception {
        var factory = new EmsAttemptApi();
        TibcoFactoryProvider.configureOutboundAttempts(factory, 1000);
        assertThat(factory.connectCount).isEqualTo(1);
        assertThat(factory.connectDelay).isZero();
        assertThat(factory.connectTimeout).isEqualTo(1000);
        assertThat(factory.reconnectCount).isEqualTo(1);
        assertThat(factory.reconnectDelay).isZero();
        assertThat(factory.reconnectTimeout).isEqualTo(1000);
        assertThatThrownBy(() -> TibcoFactoryProvider.configureOutboundAttempts(new Object(), 1000))
                .isInstanceOf(NoSuchMethodException.class);
    }

    /** Signature fixture only; this does not substitute for testing the licensed EMS client. */
    public static final class EmsAttemptApi {
        int connectCount;
        int connectDelay;
        int connectTimeout;
        int reconnectCount;
        int reconnectDelay;
        int reconnectTimeout;
        public void setConnAttemptCount(int value) { connectCount = value; }
        public void setConnAttemptDelay(int value) { connectDelay = value; }
        public void setConnAttemptTimeout(int value) { connectTimeout = value; }
        public void setReconnAttemptCount(int value) { reconnectCount = value; }
        public void setReconnAttemptDelay(int value) { reconnectDelay = value; }
        public void setReconnAttemptTimeout(int value) { reconnectTimeout = value; }
    }
}
