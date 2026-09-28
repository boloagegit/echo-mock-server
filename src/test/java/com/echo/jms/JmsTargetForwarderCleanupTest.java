package com.echo.jms;

import com.echo.config.JmsProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Isolated cleanup contracts: no broker, network, database, or Spring context. */
class JmsTargetForwarderCleanupTest {

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void deletesReplyQueueAfterClosingConsumersAndBeforeClosingSession(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            assertThat(fixture.forward()).isEqualTo("reply");

            var order = inOrder(fixture.exchange.consumer, fixture.exchange.producer,
                    fixture.exchange.replyQueue, fixture.exchange.session);
            order.verify(fixture.exchange.consumer).close();
            order.verify(fixture.exchange.producer).close();
            order.verify(fixture.exchange.replyQueue).delete();
            order.verify(fixture.exchange.session).close();
            verify(fixture.connection, never()).close();
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void deletesReplyQueueAfterTimeout(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            when(fixture.exchange.consumer.receive(1_000)).thenReturn(null);

            assertThat(fixture.forward()).isEqualTo("<error>JMS response timeout</error>");

            verify(fixture.exchange.consumer).close();
            verify(fixture.exchange.replyQueue).delete();
            verify(fixture.connection, never()).close();
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void deletesReplyQueueAfterNonTextReply(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            when(fixture.exchange.consumer.receive(1_000)).thenReturn(mock(Message.class));

            assertThat(fixture.forward()).isEqualTo("<error>JMS response timeout</error>");

            verify(fixture.exchange.replyQueue).delete();
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void deletesReplyQueueWhenProducerCreationFails(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            when(fixture.exchange.session.createProducer(fixture.exchange.destination))
                    .thenThrow(new JMSException("producer failed"));

            assertThat(fixture.forward()).contains("producer failed");

            verify(fixture.exchange.replyQueue).delete();
            verify(fixture.exchange.session).close();
            verify(fixture.connection).close();
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void deletesReplyQueueWhenSendFailsBeforeConsumerExists(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            doThrow(new JMSException("send failed")).when(fixture.exchange.producer).send(any(Message.class));

            assertThat(fixture.forward()).contains("send failed");

            verify(fixture.exchange.session, never()).createConsumer(any());
            verify(fixture.exchange.producer).close();
            verify(fixture.exchange.replyQueue).delete();
            verify(fixture.exchange.session).close();
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void deletesReplyQueueWhenConsumerCreationFails(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            when(fixture.exchange.session.createConsumer(fixture.exchange.replyQueue))
                    .thenThrow(new JMSException("consumer failed"));

            assertThat(fixture.forward()).contains("consumer failed");

            verify(fixture.exchange.replyQueue).delete();
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void deletesReplyQueueWhenReceiveFails(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            when(fixture.exchange.consumer.receive(1_000)).thenThrow(new JMSException("receive failed"));

            assertThat(fixture.forward()).contains("receive failed");

            verify(fixture.exchange.consumer).close();
            verify(fixture.exchange.replyQueue).delete();
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void cleanupFailurePreservesSuccessfulReplyAndReplacesConnection(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            doThrow(new JMSException("delete failed")).when(fixture.exchange.replyQueue).delete();

            assertThat(fixture.forward()).isEqualTo("reply");
            verify(fixture.connection).close();

            Connection replacement = mock(Connection.class);
            Exchange replacementExchange = new Exchange();
            when(fixture.factory.createConnection()).thenReturn(replacement);
            when(replacement.createSession(false, Session.AUTO_ACKNOWLEDGE))
                    .thenReturn(replacementExchange.session);
            assertThat(fixture.forward()).isEqualTo("reply");
            verify(fixture.factory, times(2)).createConnection();
            verify(replacement, never()).close();
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void cleanupFailureDoesNotReplaceTheOriginalFailure(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            when(fixture.exchange.consumer.receive(1_000)).thenThrow(new JMSException("receive failed"));
            doThrow(new JMSException("delete failed")).when(fixture.exchange.replyQueue).delete();

            assertThat(fixture.forward()).contains("receive failed").doesNotContain("delete failed");

            verify(fixture.connection).close();
        }
    }

    @ParameterizedTest
    @EnumSource(JmsForwardingRoute.class)
    void failedConsumerCloseStillAttemptsDeletionAndRetiresConnection(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            doThrow(new JMSException("consumer close failed")).when(fixture.exchange.consumer).close();
            doThrow(new JMSException("still in use")).when(fixture.exchange.replyQueue).delete();

            assertThat(fixture.forward()).contains("consumer close failed").doesNotContain("still in use");

            verify(fixture.exchange.replyQueue).delete();
            verify(fixture.exchange.session).close();
            verify(fixture.connection).close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = JmsForwardingRoute.class, names = {"DEFAULT", "SELECTED"})
    void cleanupFailureDoesNotInterruptAnotherActiveForward(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route);
             HeldForward active = new HeldForward(fixture, null)) {
            doThrow(new JMSException("delete failed")).when(fixture.exchange.replyQueue).delete();

            active.awaitReceiving();
            assertThat(fixture.forward()).isEqualTo("reply");
            verify(fixture.connection, never()).close();

            assertThat(active.complete()).isEqualTo("reply");
            verify(fixture.connection).close();
            verify(active.exchange.replyQueue).delete();
        }
    }

    @ParameterizedTest
    @EnumSource(value = JmsForwardingRoute.class, names = {"DEFAULT", "SELECTED"})
    void lateFailureFromRetiredClientDoesNotCloseItsReplacement(JmsForwardingRoute route) throws Exception {
        try (Fixture fixture = new Fixture(route)) {
            Connection replacement = mock(Connection.class);
            Exchange replacementExchange = new Exchange();
            when(fixture.factory.createConnection()).thenReturn(fixture.connection, replacement);
            when(replacement.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(replacementExchange.session);

            try (HeldForward active = new HeldForward(fixture, new JMSException("old receive failed"))) {
                doThrow(new JMSException("delete failed")).when(fixture.exchange.replyQueue).delete();
                doThrow(new JMSException("old delete failed")).when(active.exchange.replyQueue).delete();

                active.awaitReceiving();
                assertThat(fixture.forward()).isEqualTo("reply"); // retires old connection
                assertThat(fixture.forward()).isEqualTo("reply"); // creates replacement
                verify(fixture.connection, never()).close();

                assertThat(active.complete()).contains("old receive failed");
                verify(fixture.connection).close();
                verify(replacement, never()).close();
                assertThat(fixture.forward()).isEqualTo("reply");
                verify(fixture.factory, times(2)).createConnection();
            }
        }
    }

    /** Owns its worker and always releases it, including when a test assertion fails. */
    private static final class HeldForward implements AutoCloseable {
        final Exchange exchange = new Exchange();
        private final CountDownLatch receiving = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<String> result;

        HeldForward(Fixture fixture, JMSException receiveFailure) throws Exception {
            when(fixture.connection.createSession(false, Session.AUTO_ACKNOWLEDGE))
                    .thenReturn(exchange.session, fixture.exchange.session);
            when(exchange.consumer.receive(1_000)).thenAnswer(invocation -> {
                receiving.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new JMSException("test deadline");
                if (receiveFailure != null) throw receiveFailure;
                return exchange.response;
            });
            result = executor.submit(fixture::forward);
        }

        void awaitReceiving() throws InterruptedException {
            assertThat(receiving.await(2, TimeUnit.SECONDS)).as("forward entered receive").isTrue();
        }

        String complete() throws Exception {
            release.countDown();
            return result.get(3, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS))
                    .as("forward worker stopped").isTrue();
        }
    }

    private static final class Exchange {
        final Session session = mock(Session.class);
        final Queue destination = mock(Queue.class);
        final TemporaryQueue replyQueue = mock(TemporaryQueue.class);
        final MessageProducer producer = mock(MessageProducer.class);
        final MessageConsumer consumer = mock(MessageConsumer.class);
        final TextMessage response = mock(TextMessage.class);

        Exchange() throws Exception {
            when(session.createQueue("TARGET.REQUEST")).thenReturn(destination);
            when(session.createTemporaryQueue()).thenReturn(replyQueue);
            when(session.createProducer(destination)).thenReturn(producer);
            when(session.createTextMessage(any())).thenReturn(mock(TextMessage.class));
            when(session.createConsumer(replyQueue)).thenReturn(consumer);
            when(consumer.receive(1_000)).thenReturn(response);
            when(response.getText()).thenReturn("reply");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JmsForwardingRoute route;
        final ConnectionFactory factory = mock(ConnectionFactory.class);
        final Connection connection = mock(Connection.class);
        final Exchange exchange = new Exchange();
        final JmsTargetForwarder forwarder;

        Fixture(JmsForwardingRoute route) throws Exception {
            this.route = route;
            JmsProperties props = new JmsProperties();
            JmsProperties.Target target = props.getTarget();
            target.setEnabled(true);
            target.setType("artemis");
            target.setServerUrl("vm://cleanup-test");
            target.setQueue("TARGET.REQUEST");
            target.setTimeoutSeconds(1);
            JmsTargetFactoryProvider provider = mock(JmsTargetFactoryProvider.class);
            when(provider.supports("artemis")).thenReturn(true);
            when(provider.create(any())).thenReturn(factory);
            when(factory.createConnection()).thenReturn(connection);
            when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(exchange.session);
            forwarder = route.createForwarder(props, List.of(provider));
        }

        String forward() {
            return route.forward(forwarder, "request");
        }

        @Override
        public void close() {
            forwarder.cleanup();
        }
    }
}
