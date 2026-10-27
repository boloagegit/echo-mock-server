package com.echo.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.echo.config.JmsProperties;

import jakarta.jms.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import java.util.List;
import java.util.concurrent.*;

/** Real Spring workers can be active while destination registration is still blocked. */
class JmsConsumerRegistrationStateTest {
    @Test
    void activeWorkerIsNotReceivingUntilConsumerRegistrationCompletes() throws Exception {
        CountDownLatch entering = new CountDownLatch(1), release = new CountDownLatch(1);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        MessageConsumer consumer = mock(MessageConsumer.class);
        Session session =
                mock(
                        Session.class,
                        invocation -> {
                            if (invocation.getMethod().getName().equals("createConsumer")) {
                                entering.countDown();
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new JMSException("fixture deadline");
                                return consumer;
                            }
                            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
                        });
        when(consumer.receive(anyLong()))
                .thenAnswer(
                        i -> {
                            Thread.sleep(20);
                            return null;
                        });
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createSession(anyBoolean(), anyInt())).thenReturn(session);
        when(session.createQueue("REQUEST")).thenReturn(mock(Queue.class));
        var container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.setDestinationName("REQUEST");
        container.setSessionTransacted(true);
        container.setConcurrency("1-1");
        container.setMessageListener((MessageListener) m -> {});
        container.afterPropertiesSet();
        try {
            container.start();
            assertThat(entering.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(container.getActiveConsumerCount()).isEqualTo(1);
            assertThat(container.isRegisteredWithDestination()).isFalse();
            assertThat(manager(container).listenerState())
                    .isEqualTo(JmsConnectionManager.ListenerState.RECONNECTING);
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!container.isRegisteredWithDestination() && System.nanoTime() < deadline)
                Thread.sleep(5);
            assertThat(container.isRegisteredWithDestination()).isTrue();
            assertThat(manager(container).listenerState())
                    .isEqualTo(JmsConnectionManager.ListenerState.RECEIVING);
        } finally {
            release.countDown();
            container.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void nonCachingContainersKeepTheirExistingActiveWorkerFallback(int cache) {
        var c = mock(DefaultMessageListenerContainer.class);
        when(c.isRunning()).thenReturn(true);
        when(c.getCacheLevel()).thenReturn(cache);
        when(c.getActiveConsumerCount()).thenReturn(1);
        assertThat(manager(c).listenerState())
                .isEqualTo(JmsConnectionManager.ListenerState.RECEIVING);
        verify(c, never()).isRegisteredWithDestination();
    }

    @Test
    void registrationDoesNotOverrideRecoveryOrStoppedState() {
        var c = mock(DefaultMessageListenerContainer.class);
        when(c.isRunning()).thenReturn(true);
        when(c.getCacheLevel()).thenReturn(3);
        when(c.getActiveConsumerCount()).thenReturn(1);
        when(c.isRegisteredWithDestination()).thenReturn(true);
        when(c.isRecovering()).thenReturn(true);
        assertThat(manager(c).listenerState())
                .isEqualTo(JmsConnectionManager.ListenerState.RECONNECTING);
        when(c.isRecovering()).thenReturn(false);
        when(c.isRunning()).thenReturn(false);
        assertThat(manager(c).listenerState())
                .isEqualTo(JmsConnectionManager.ListenerState.RECONNECTING);
    }

    @Test
    void registeredConsumerWithoutAnActiveWorkerIsStillNotReceiving() {
        var c = mock(DefaultMessageListenerContainer.class);
        when(c.isRunning()).thenReturn(true);
        when(c.getCacheLevel()).thenReturn(3);
        when(c.isRegisteredWithDestination()).thenReturn(true);
        when(c.getActiveConsumerCount()).thenReturn(0);
        assertThat(manager(c).listenerState())
                .isEqualTo(JmsConnectionManager.ListenerState.RECONNECTING);
    }

    @SuppressWarnings("unchecked")
    static JmsConnectionManager manager(DefaultMessageListenerContainer c) {
        var registry = mock(JmsListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenReturn(List.of(c));
        ObjectProvider<JmsListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new JmsConnectionManager(
                new JmsProperties(), mock(ConnectionFactory.class), provider);
    }
}
