package com.echo.jms.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.echo.config.JmsProperties;

import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.DefaultEventExecutor;

import org.apache.activemq.artemis.api.core.ActiveMQBuffers;
import org.apache.activemq.artemis.api.core.ActiveMQConnectionTimedOutException;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException;
import org.apache.activemq.artemis.api.core.Interceptor;
import org.apache.activemq.artemis.core.protocol.core.Channel;
import org.apache.activemq.artemis.core.protocol.core.impl.ActiveMQClientProtocolManager;
import org.apache.activemq.artemis.core.protocol.core.impl.ActiveMQClientProtocolManagerFactory;
import org.apache.activemq.artemis.core.protocol.core.impl.PacketImpl;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnection;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.spi.core.remoting.Connection;
import org.apache.activemq.artemis.spi.core.remoting.TopologyResponseHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class ArtemisClosedTransportHandshakeTest {
    @Test
    void aCustomProtocolFactoryIsPreserved() {
        try (var factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:61616")) {
            var custom = new ActiveMQClientProtocolManagerFactory() {};
            factory.getServerLocator().setProtocolManagerFactory(custom);

            ArtemisFactoryProvider.installHandshakeCloseGuard(factory);

            assertThat(factory.getServerLocator().getProtocolManagerFactory()).isSameAs(custom);
        }
    }

    @Test
    void aCloseBeforeTheRpcWaitIsNotLostWithoutTheFactoryCallback() throws Exception {
        try (var fixture = new ProtocolFixture()) {
            fixture.connect(List.of());
            fixture.beforeBuffer = fixture::closeTransport;
            assertTimeout(
                    Duration.ofMillis(1500),
                    () ->
                            assertThrows(
                                    ActiveMQException.class,
                                    () -> fixture.manager.sendConnect(null, null, null)));
        }
    }

    @Test
    void aDeliveredSecurityErrorIsNotReplacedByTheCloseNotification() throws Exception {
        try (var fixture = new ProtocolFixture()) {
            fixture.connect(List.of());
            var denied =
                    new org.apache.activemq.artemis.api.core.ActiveMQSecurityException("denied");
            fixture.onWrite =
                    () -> {
                        fixture.manager
                                .getChannel1()
                                .handlePacket(
                                        new org.apache.activemq.artemis.core.protocol.core.impl
                                                .wireformat.ActiveMQExceptionMessage(denied));
                        fixture.closeTransport();
                    };
            assertThatThrownBy(() -> fixture.manager.sendConnect(null, null, null))
                    .isSameAs(denied);
            fixture.eventLoop.submit(() -> {}).syncUninterruptibly();
        }
    }

    @Test
    void aCustomOutgoingInterceptorKeepsItsOriginalLockingEvenWhenAddedAfterConnect()
            throws Exception {
        try (var fixture = new ProtocolFixture()) {
            var interceptors = new java.util.concurrent.CopyOnWriteArrayList<Interceptor>();
            fixture.connect(interceptors);
            interceptors.add(
                    (packet, connection) -> {
                        if (packet.getType() != PacketImpl.CONNECT) return true;
                        fixture.closeTransport();
                        var marker = new CountDownLatch(1);
                        fixture.eventLoop.execute(marker::countDown);
                        fixture.await(marker);
                        return false;
                    });
            var error =
                    assertThrows(
                            ActiveMQException.class,
                            () -> fixture.manager.sendConnect(null, null, null));
            assertThat(error.getType())
                    .isEqualTo(
                            org.apache.activemq.artemis.api.core.ActiveMQExceptionType
                                    .INTERCEPTOR_REJECTED_PACKET);
        }
    }

    @Test
    void aCustomOutgoingRejectionKeepsItsErrorTypeOnAnAlreadyClosedTransport() throws Exception {
        try (var fixture = new ProtocolFixture()) {
            var calls = new AtomicInteger();
            fixture.connect(
                    List.of(
                            (packet, connection) -> {
                                calls.incrementAndGet();
                                return false;
                            }));
            fixture.closeTransport();
            var error =
                    assertThrows(
                            ActiveMQException.class,
                            () -> fixture.manager.sendConnect(null, null, null));
            assertThat(error.getType())
                    .isEqualTo(ActiveMQExceptionType.INTERCEPTOR_REJECTED_PACKET);
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    private static final class ProtocolFixture implements AutoCloseable {
        final ActiveMQConnectionFactory factory;
        final ActiveMQClientProtocolManager manager;
        final DefaultEventExecutor eventLoop = new DefaultEventExecutor();
        final NettyConnection transport = mock(NettyConnection.class);
        final AtomicBoolean open = new AtomicBoolean(true);
        final CountDownLatch closeObserved = new CountDownLatch(1);
        final DefaultChannelPromise closeFuture;
        Runnable beforeBuffer = () -> {};
        Runnable onWrite = () -> {};

        ProtocolFixture() throws Exception {
            var target = new JmsProperties.Target();
            target.setServerUrl("tcp://127.0.0.1:61616?callTimeout=2000");
            factory = (ActiveMQConnectionFactory) new ArtemisFactoryProvider().create(target);
            manager =
                    (ActiveMQClientProtocolManager)
                            factory.getServerLocator()
                                    .getProtocolManagerFactory()
                                    .newProtocolManager();
            manager.setExecutor(Runnable::run);
            var nettyChannel = mock(io.netty.channel.Channel.class);
            closeFuture = new DefaultChannelPromise(nettyChannel, eventLoop);
            when(nettyChannel.closeFuture()).thenReturn(closeFuture);
            when(transport.isOpen()).thenAnswer(invocation -> open.get());
            when(transport.getChannel()).thenReturn(nettyChannel);
            when(transport.getID()).thenReturn("closing-handshake");
            when(transport.createTransportBuffer(anyInt()))
                    .thenAnswer(
                            invocation -> {
                                beforeBuffer.run();
                                return ActiveMQBuffers.dynamicBuffer(
                                        invocation.getArgument(0, Integer.class));
                            });
            doAnswer(
                            invocation -> {
                                onWrite.run();
                                return null;
                            })
                    .when(transport)
                    .write(
                            any(org.apache.activemq.artemis.api.core.ActiveMQBuffer.class),
                            eq(false),
                            eq(false));
            closeFuture.addListener(ignored -> closeObserved.countDown());
        }

        void connect(List<Interceptor> outgoing) {
            manager.connect(
                    transport,
                    2000,
                    2000,
                    List.of(),
                    outgoing,
                    mock(TopologyResponseHandler.class));
        }

        void closeTransport() {
            open.set(false);
            closeFuture.trySuccess();
            await(closeObserved);
        }

        void await(CountDownLatch latch) {
            try {
                assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Override
        public void close() {
            if (manager.getCurrentConnection() != null) manager.getCurrentConnection().destroy();
            factory.close();
            eventLoop.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void aTransportClosedBeforeConnectionPublicationNeverWaitsForAProtocolReply() throws Exception {
        var target = new JmsProperties.Target();
        target.setServerUrl("tcp://127.0.0.1:61616?callTimeout=9000&reconnectAttempts=20");
        try (var factory =
                (ActiveMQConnectionFactory) new ArtemisFactoryProvider().create(target)) {
            var manager =
                    spy(
                            (ActiveMQClientProtocolManager)
                                    factory.getServerLocator()
                                            .getProtocolManagerFactory()
                                            .newProtocolManager());
            RemotingConnection current = mock(RemotingConnection.class);
            Connection transport = mock(Connection.class);
            Channel channel = mock(Channel.class);
            doReturn(current).when(manager).getCurrentConnection();
            when(current.getTransportConnection()).thenReturn(transport);
            when(transport.isOpen()).thenReturn(false);
            doReturn(channel).when(manager).getChannel1();
            when(channel.sendBlocking(any(), eq(PacketImpl.CONNECT_RESPONSE)))
                    .thenThrow(
                            new ActiveMQConnectionTimedOutException(
                                    "Closed transport waited for CONNECT"));

            assertThatThrownBy(() -> manager.sendConnect("node", "user", "password"))
                    .isInstanceOf(ActiveMQNotConnectedException.class);

            verifyNoInteractions(channel);
            assertThat(factory.getCallTimeout()).isEqualTo(9000);
            assertThat(factory.getReconnectAttempts()).isEqualTo(20);
        }
    }

    @Test
    void anOpenTransportRetainsTheProviderProtocolAndItsTimeout() throws Exception {
        var target = new JmsProperties.Target();
        target.setServerUrl("tcp://127.0.0.1:61616?callTimeout=9000");
        try (var factory =
                (ActiveMQConnectionFactory) new ArtemisFactoryProvider().create(target)) {
            var manager =
                    spy(
                            (ActiveMQClientProtocolManager)
                                    factory.getServerLocator()
                                            .getProtocolManagerFactory()
                                            .newProtocolManager());
            RemotingConnection current = mock(RemotingConnection.class);
            Connection transport = mock(Connection.class);
            Channel channel = mock(Channel.class);
            doReturn(current).when(manager).getCurrentConnection();
            when(current.getTransportConnection()).thenReturn(transport);
            when(transport.isOpen()).thenReturn(true);
            doReturn(channel).when(manager).getChannel1();
            var failure = new ActiveMQConnectionTimedOutException("Provider timeout");
            when(channel.sendBlocking(any(), eq(PacketImpl.CONNECT_RESPONSE))).thenThrow(failure);

            assertThatThrownBy(() -> manager.sendConnect("node", "user", "password"))
                    .isSameAs(failure);

            verify(channel).sendBlocking(any(), eq(PacketImpl.CONNECT_RESPONSE));
            assertThat(factory.getCallTimeout()).isEqualTo(9000);
        }
    }
}
