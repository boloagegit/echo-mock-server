package com.echo.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.echo.config.JmsProperties;
import com.echo.jms.target.JmsTargetFactoryProvider;

import jakarta.jms.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Provider notifications must stop new borrowing without closing another user's session. */
class JmsConnectionFailureRetirementTest {
    static Stream<Arguments> routes() {
        return Stream.of(JmsForwardingRoute.DEFAULT, JmsForwardingRoute.SELECTED)
                .flatMap(r -> Stream.of(0, 8).map(n -> Arguments.of(r, n)));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void idleFailureUsesAFreshConnectionBeforeTheNextBusinessSend(
            JmsForwardingRoute route, int cache) throws Exception {
        try (var f = new Fixture(route, cache)) {
            assertThat(f.forward()).isEqualTo("reply");
            f.notifyFailure();
            verify(f.old.connection, never()).close();
            assertThat(f.forward()).isEqualTo("reply");
            verify(f.factory, times(2)).createConnection();
            assertThat(sends(f.old.producer)).isEqualTo(1);
            verify(f.old.connection).close();
            assertThat(f.metrics.snapshot()).containsEntry("forwardFailures", 0L);
        }
    }

    @ParameterizedTest
    @MethodSource("routes")
    void failureCallbackIsNonBlockingAndActiveUsersFinishBeforePhysicalClose(
            JmsForwardingRoute route, int cache) throws Exception {
        try (var f = new Fixture(route, cache)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            when(f.old.consumer.receive(1000))
                    .thenAnswer(
                            i -> {
                                entered.countDown();
                                if (!release.await(8, TimeUnit.SECONDS))
                                    throw new JMSException("test deadline");
                                return f.old.response;
                            });
            try {
                Future<String> active = pool.submit(f::forward);
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                pool.submit(
                                () -> {
                                    f.notifyFailure();
                                    return null;
                                })
                        .get(1, TimeUnit.SECONDS);
                verify(f.old.connection, never()).close();
                assertThat(f.forward()).isEqualTo("reply");
                verify(f.old.connection, never()).close();
                verify(f.fresh.connection, never()).close();
                release.countDown();
                assertThat(active.get(3, TimeUnit.SECONDS)).isEqualTo("reply");
                verify(f.old.connection).close();
                assertThat(f.metrics.snapshot())
                        .containsEntry("forwardActive", 0)
                        .containsEntry("cleanupFailures", 0L);
            } finally {
                release.countDown();
                pool.shutdownNow();
                assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("routes")
    void lateNotificationsFromAnEvictedClientCannotRetireItsReplacement(
            JmsForwardingRoute route, int cache) throws Exception {
        try (var f = new Fixture(route, cache)) {
            assertThat(f.forward()).isEqualTo("reply");
            f.forwarder.evict(7L);
            assertThat(f.forward()).isEqualTo("reply");
            f.notifyFailure();
            assertThat(f.forward()).isEqualTo("reply");
            verify(f.factory, times(2)).createConnection();
            verify(f.fresh.connection, never()).close();
        }
    }

    @ParameterizedTest
    @MethodSource("routes")
    void sendFailureIsReportedWithoutResendingTheBusinessMessage(
            JmsForwardingRoute route, int cache) throws Exception {
        try (var f = new Fixture(route, cache)) {
            doThrow(new JMSException("ambiguous send"))
                    .when(f.old.producer)
                    .send(any(Message.class), anyInt(), anyInt(), anyLong());
            doThrow(new JMSException("ambiguous send"))
                    .when(f.old.producer)
                    .send(any(Message.class));
            assertThat(f.forward()).contains("ambiguous send");
            verify(f.factory, times(1)).createConnection();
            assertThat(sends(f.old.producer)).isEqualTo(1);
            assertThat(sends(f.fresh.producer)).isZero();
            assertThat(f.forward()).isEqualTo("reply");
            verify(f.factory, times(2)).createConnection();
        }
    }

    @ParameterizedTest
    @MethodSource("routes")
    void notificationDuringConnectionStartDoesNotWaitForTheOwnerMonitor(
            JmsForwardingRoute route, int cache) throws Exception {
        try (var f = new Fixture(route, cache)) {
            doAnswer(
                            i -> {
                                var pool = Executors.newSingleThreadExecutor();
                                try {
                                    pool.submit(
                                                    () -> {
                                                        f.notifyFailure();
                                                        return null;
                                                    })
                                            .get(1, TimeUnit.SECONDS);
                                } finally {
                                    pool.shutdownNow();
                                    assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
                                }
                                return null;
                            })
                    .when(f.old.connection)
                    .start();
            assertThat(f.forward()).isEqualTo("reply");
            assertThat(f.forward()).isEqualTo("reply");
            verify(f.factory, times(2)).createConnection();
            verify(f.old.connection).close();
            verify(f.fresh.connection, never()).close();
        }
    }

    static long sends(MessageProducer producer) {
        return mockingDetails(producer).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .count();
    }

    static final class Exchange {
        final Connection connection = mock(Connection.class);
        final Session session = mock(Session.class);
        final TemporaryQueue replyQueue = mock(TemporaryQueue.class);
        final Queue destination = mock(Queue.class);
        final MessageProducer producer = mock(MessageProducer.class);
        final MessageConsumer consumer = mock(MessageConsumer.class);
        final TextMessage response = mock(TextMessage.class);
        final AtomicReference<ExceptionListener> listener = new AtomicReference<>();

        Exchange() throws Exception {
            doAnswer(
                            i -> {
                                listener.set(i.getArgument(0));
                                return null;
                            })
                    .when(connection)
                    .setExceptionListener(any(ExceptionListener.class));
            when(connection.createSession(false, Session.AUTO_ACKNOWLEDGE)).thenReturn(session);
            when(session.createQueue("TARGET.REQUEST")).thenReturn(destination);
            when(session.createTemporaryQueue()).thenReturn(replyQueue);
            when(session.createProducer(destination)).thenReturn(producer);
            when(session.createTextMessage(anyString())).thenReturn(mock(TextMessage.class));
            when(session.createConsumer(replyQueue)).thenReturn(consumer);
            when(consumer.receive(1000)).thenReturn(response);
            when(response.getText()).thenReturn("reply");
        }
    }

    static final class Fixture implements AutoCloseable {
        final JmsForwardingRoute route;
        final Exchange old = new Exchange(), fresh = new Exchange();
        final ConnectionFactory factory = mock(ConnectionFactory.class);
        final JmsTargetForwarder forwarder;
        final JmsRuntimeMetrics metrics =
                new JmsRuntimeMetrics(new com.echo.config.MonitoringProperties());

        Fixture(JmsForwardingRoute route, int cache) throws Exception {
            this.route = route;
            var props = new JmsProperties();
            props.getForward().setSessionCacheSize(cache);
            var target = props.getTarget();
            target.setEnabled(true);
            target.setType("artemis");
            target.setServerUrl("vm://retirement-test");
            target.setQueue("TARGET.REQUEST");
            target.setTimeoutSeconds(1);
            var provider = mock(JmsTargetFactoryProvider.class);
            when(provider.supports("artemis")).thenReturn(true);
            when(provider.create(any())).thenReturn(factory);
            when(factory.createConnection()).thenReturn(old.connection, fresh.connection);
            forwarder = route.createForwarder(props, List.of(provider), metrics);
        }

        void notifyFailure() {
            assertThat(old.listener.get()).as("provider failure listener installed").isNotNull();
            old.listener.get().onException(new JMSException("transport failed"));
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
