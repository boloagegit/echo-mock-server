package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;
import com.echo.service.JmsTargetConnectionService;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class JmsTargetForwarderSelectionTest {

    @Test
    void matchedRuleResolvesItsExplicitProfileInsteadOfTheDefault() throws Exception {
        JmsTargetConnectionService service = mock(JmsTargetConnectionService.class);
        JmsTargetFactoryProvider provider = mock(JmsTargetFactoryProvider.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        JmsProperties.Target selected = target("tcp://selected:61616");

        when(service.resolveEnabled("7")).thenReturn(
                new JmsTargetConnectionService.ResolvedTarget(
                        "db:7:0", "Selected", selected, false));
        when(provider.supports("artemis")).thenReturn(true);
        when(provider.create(any())).thenReturn(factory);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(false, jakarta.jms.Session.AUTO_ACKNOWLEDGE))
                .thenThrow(new JMSException("selected unavailable"));

        JmsTargetForwarder forwarder = new JmsTargetForwarder(service, List.of(provider));

        assertThat(forwarder.forward("request", null, "7", false))
                .contains("selected unavailable");
        verify(service).resolveEnabled("7");
        verify(service, never()).resolveActive();
    }

    @Test
    void changingDefaultProfileRebuildsTheTargetFactoryOnNextForward() throws Exception {
        JmsTargetConnectionService service = mock(JmsTargetConnectionService.class);
        JmsTargetFactoryProvider provider = mock(JmsTargetFactoryProvider.class);
        ConnectionFactory firstFactory = mock(ConnectionFactory.class);
        ConnectionFactory secondFactory = mock(ConnectionFactory.class);
        Connection firstConnection = mock(Connection.class);
        Connection secondConnection = mock(Connection.class);
        JmsProperties.Target first = target("tcp://first:61616");
        JmsProperties.Target second = target("tcp://second:61616");

        when(provider.supports("artemis")).thenReturn(true);
        when(provider.create(any())).thenReturn(firstFactory, secondFactory);
        when(firstFactory.createConnection()).thenReturn(firstConnection);
        when(secondFactory.createConnection()).thenReturn(secondConnection);
        when(firstConnection.createSession(false, jakarta.jms.Session.AUTO_ACKNOWLEDGE))
                .thenThrow(new JMSException("first unavailable"));
        when(secondConnection.createSession(false, jakarta.jms.Session.AUTO_ACKNOWLEDGE))
                .thenThrow(new JMSException("second unavailable"));
        when(service.resolveActive())
                .thenReturn(Optional.of(new JmsTargetConnectionService.ResolvedTarget(
                        "db:1:0", "First", first, false)))
                .thenReturn(Optional.of(new JmsTargetConnectionService.ResolvedTarget(
                        "db:2:0", "Second", second, false)));

        JmsTargetForwarder forwarder = new JmsTargetForwarder(service, List.of(provider));

        assertThat(forwarder.forward("request-1", null)).contains("<error>");
        assertThat(forwarder.forward("request-2", null)).contains("<error>");

        verify(provider, times(2)).create(any());
        verify(firstConnection).close();
        verify(secondConnection).close();
    }

    private static JmsProperties.Target target(String url) {
        JmsProperties.Target target = new JmsProperties.Target();
        target.setEnabled(true);
        target.setType("artemis");
        target.setServerUrl(url);
        target.setQueue("TARGET.REQUEST");
        target.setTimeoutSeconds(5);
        return target;
    }
}
