package com.echo.config;

import com.echo.jms.target.ArtemisFactoryProvider;
import com.echo.jms.target.TibcoFactoryProvider;
import com.echo.jms.target.JmsTargetFactoryProvider;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jms.connection.CachingConnectionFactory;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ExternalJmsSourceConfigTest {

    private final ExternalJmsSourceConfig config = new ExternalJmsSourceConfig();

    @Test
    void resetsKeepProviderOpenAndFinalDestructionClosesItOnceAfterConnection() throws Exception {
        var target = mock(ConnectionFactory.class, withSettings().extraInterfaces(AutoCloseable.class));
        var first = mock(Connection.class);
        var second = mock(Connection.class);
        when(target.createConnection()).thenReturn(first, second);
        var caching = caching(target);
        try {
            caching.createConnection().close();
            caching.resetConnection();
            verify(first).close();
            verify((AutoCloseable) target, never()).close();
            caching.createConnection().close();
        } finally {
            caching.destroy();
        }
        var order = inOrder(second, target);
        order.verify(second).close();
        order.verify((AutoCloseable) target).close();
        caching.destroy();
        verify((AutoCloseable) target).close();
    }

    @Test
    void providerCloseFailureDoesNotPreventPhysicalConnectionCleanup() throws Exception {
        var target = mock(ConnectionFactory.class, withSettings().extraInterfaces(AutoCloseable.class));
        var connection = mock(Connection.class);
        when(target.createConnection()).thenReturn(connection);
        doThrow(new Exception("provider close failed")).when((AutoCloseable) target).close();
        var caching = caching(target);
        caching.createConnection().close();
        caching.destroy();
        verify(connection).close();
        verify((AutoCloseable) target).close();
    }

    @Test
    void physicalCloseFailureStillClosesProviderFactory() throws Exception {
        var target = mock(ConnectionFactory.class, withSettings().extraInterfaces(AutoCloseable.class));
        var connection = mock(Connection.class);
        when(target.createConnection()).thenReturn(connection);
        doThrow(new jakarta.jms.JMSException("connection close failed")).when(connection).close();
        var caching = caching(target);
        caching.createConnection().close();
        caching.destroy();
        verify((AutoCloseable) target).close();
    }

    @Test
    void standardFactoryWithoutAutoCloseableKeepsNormalConnectionLifecycle() throws Exception {
        var target = mock(ConnectionFactory.class);
        var connection = mock(Connection.class);
        when(target.createConnection()).thenReturn(connection);
        var caching = caching(target);
        caching.createConnection().close();
        caching.destroy();
        verify(connection).close();
    }

    private CachingConnectionFactory caching(ConnectionFactory target) throws Exception {
        var provider = mock(JmsTargetFactoryProvider.class);
        when(provider.supports("artemis")).thenReturn(true);
        when(provider.create(any())).thenReturn(target);
        return (CachingConnectionFactory) config.jmsConnectionFactory(
                properties("artemis", "tcp://test:61616"), List.of(provider));
    }

    @Test
    void createsCachingFactoryForExternalArtemis() throws Exception {
        JmsProperties properties = properties("artemis", "tcp://esb:61616");

        ConnectionFactory factory = config.jmsConnectionFactory(properties,
                List.of(new TibcoFactoryProvider(), new ArtemisFactoryProvider()));
        try {
            assertThat(factory).isInstanceOf(CachingConnectionFactory.class);
            assertThat(((CachingConnectionFactory) factory).getTargetConnectionFactory())
                    .isInstanceOf(org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory.class);
        } finally {
            ((CachingConnectionFactory) factory).destroy();
        }
    }

    @Test
    void typeIsTrimmedAndCaseInsensitive() throws Exception {
        JmsProperties properties = properties(" Artemis ", "tcp://esb:61616");

        var caching = (CachingConnectionFactory) config.jmsConnectionFactory(properties, List.of(new ArtemisFactoryProvider()));
        try {
            assertThat(caching.getTargetConnectionFactory()).isNotNull();
        } finally {
            caching.destroy();
        }
    }

    @Test
    void outboundOptInNeverOverridesSourceRetryOrCallSettings() throws Exception {
        JmsProperties properties = properties("artemis",
                "tcp://esb:61616?callTimeout=9000&reconnectAttempts=20");
        properties.getForward().setBoundedWaitsEnabled(true);
        var caching = (CachingConnectionFactory) config.jmsConnectionFactory(properties,
                List.of(new ArtemisFactoryProvider()));
        try {
            var source = (ActiveMQConnectionFactory) Objects.requireNonNull(caching.getTargetConnectionFactory());
            assertThat(source.getCallTimeout()).isEqualTo(9000);
            assertThat(source.getReconnectAttempts()).isEqualTo(20);
        } finally {
            caching.destroy();
        }
    }

    @Test
    void missingServerUrlFailsAtStartup() {
        JmsProperties properties = properties("tibco", " ");

        assertThatThrownBy(() -> config.jmsConnectionFactory(properties, List.of(new TibcoFactoryProvider())))
                .hasMessageContaining("echo.jms.source.server-url");
    }

    @Test
    void unsupportedTypeFailsAtStartup() {
        JmsProperties properties = properties("rabbit", "tcp://esb:5672");

        assertThatThrownBy(() -> config.jmsConnectionFactory(properties, List.of(new ArtemisFactoryProvider())))
                .hasMessageContaining("Unsupported echo.jms.source.type: rabbit");
    }

    @Test
    void tooShortReconnectIntervalFailsAtStartup() {
        JmsProperties properties = properties("artemis", "tcp://esb:61616");
        properties.getSource().setReconnectIntervalMs(10);

        assertThatThrownBy(() -> config.jmsConnectionFactory(properties, List.of(new ArtemisFactoryProvider())))
                .hasMessageContaining("reconnect-interval-ms");
    }

    @Test
    void embeddedAndBlankAreNotExternal() {
        assertThat(JmsProperties.Source.isExternalType(null)).isFalse();
        assertThat(JmsProperties.Source.isExternalType(" ")).isFalse();
        assertThat(JmsProperties.Source.isExternalType(" EMBEDDED ")).isFalse();
        assertThat(JmsProperties.Source.isExternalType("tibco")).isTrue();
    }

    private static JmsProperties properties(String type, String url) {
        JmsProperties properties = new JmsProperties();
        properties.getSource().setType(type);
        properties.getSource().setServerUrl(url);
        properties.getSource().setUsername("echo");
        properties.getSource().setPassword("secret");
        return properties;
    }
}
