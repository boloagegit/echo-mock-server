package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JmsOutboundPolicySelectionTest {
    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void existingTargetsKeepTheirProviderRetryAndFailoverPolicyByDefault(JmsForwardingRoute route)
            throws Exception {
        verifyPolicy(route, false);
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void boundedWaitingRequiresExplicitOptIn(JmsForwardingRoute route) throws Exception {
        verifyPolicy(route, true);
    }

    private void verifyPolicy(JmsForwardingRoute route, boolean bounded) throws Exception {
        var properties = new JmsProperties();
        properties.getTarget().setEnabled(true);
        properties.getTarget().setType("artemis");
        properties.getTarget().setServerUrl("tcp://esb:61616?reconnectAttempts=20");
        if (bounded) {
            properties.getForward().setBoundedWaitsEnabled(true);
        }
        var provider = mock(JmsTargetFactoryProvider.class);
        var factory = mock(ConnectionFactory.class);
        when(provider.supports("artemis")).thenReturn(true);
        if (bounded) {
            when(provider.createOutbound(any())).thenReturn(factory);
        } else {
            when(provider.create(any())).thenReturn(factory);
        }
        when(factory.createConnection()).thenThrow(new JMSException("test connection unavailable"));

        var forwarder = route.createForwarder(properties, List.of(provider));
        try {
            assertThat(route.forward(forwarder, "request")).contains("test connection unavailable");
            if (bounded) {
                verify(provider).createOutbound(any());
                verify(provider, never()).create(any());
            } else {
                verify(provider).create(any());
                verify(provider, never()).createOutbound(any());
            }
        } finally {
            forwarder.cleanup();
        }
    }
}
