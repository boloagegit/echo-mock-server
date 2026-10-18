package com.echo.service;

import com.echo.config.JmsProperties;
import com.echo.jms.JmsConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JmsSourceHealthIndicatorTest {
    @Mock
    private ObjectProvider<JmsConnectionManager> provider;
    @Mock
    private JmsConnectionManager manager;

    @Test
    void disabledJmsDoesNotResolveOrProbeAConnection() {
        var indicator = new JmsSourceHealthIndicator(new JmsProperties(), provider);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        verifyNoInteractions(provider, manager);
    }

    @Test
    void enabledJmsWithoutAListenerIsNotReady() {
        assertThat(indicator().health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @ParameterizedTest
    @EnumSource(JmsConnectionManager.ListenerState.class)
    void observesTheExistingListenerWithoutCreatingOrTestingAnotherConnection(
            JmsConnectionManager.ListenerState state) {
        when(provider.getIfAvailable()).thenReturn(manager);
        when(manager.listenerState()).thenReturn(state);

        var health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(state == JmsConnectionManager.ListenerState.RECEIVING
                ? Status.UP : Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).isEmpty();
        verify(manager).listenerState();
        verifyNoMoreInteractions(manager);
    }

    @Test
    void listenerFailureIsUnhealthyWithoutExposingProviderData() {
        when(provider.getIfAvailable()).thenReturn(manager);
        when(manager.listenerState()).thenThrow(new IllegalStateException("private provider data"));

        var health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }

    private JmsSourceHealthIndicator indicator() {
        JmsProperties properties = new JmsProperties();
        properties.setEnabled(true);
        return new JmsSourceHealthIndicator(properties, provider);
    }
}
