package com.echo.jms.target;

import com.echo.config.JmsProperties;

import io.netty.channel.ChannelFutureListener;

import jakarta.jms.ConnectionFactory;

import lombok.extern.slf4j.Slf4j;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException;
import org.apache.activemq.artemis.api.core.Interceptor;
import org.apache.activemq.artemis.core.protocol.core.impl.ActiveMQClientProtocolManager;
import org.apache.activemq.artemis.core.protocol.core.impl.ActiveMQClientProtocolManagerFactory;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnection;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.spi.core.remoting.ClientProtocolManager;
import org.apache.activemq.artemis.spi.core.remoting.Connection;
import org.apache.activemq.artemis.spi.core.remoting.TopologyResponseHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BooleanSupplier;

@Component
@Slf4j
public class ArtemisFactoryProvider implements JmsTargetFactoryProvider {

    @Override
    public boolean supports(String type) {
        return "artemis".equalsIgnoreCase(type);
    }

    @Override
    public ConnectionFactory create(JmsProperties.Target target) {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(target.getServerUrl());
        if (target.getUsername() != null && !target.getUsername().isBlank()) {
            factory.setUser(target.getUsername());
            factory.setPassword(target.getPassword());
        }
        installHandshakeCloseGuard(factory);
        log.info("Created Artemis ConnectionFactory for: {}", target.getServerUrl());
        return factory;
    }

    static void installHandshakeCloseGuard(ActiveMQConnectionFactory factory) {
        var locator = factory.getServerLocator();
        // Respect an explicitly supplied protocol implementation.
        if (locator.getProtocolManagerFactory().getClass()
                == ActiveMQClientProtocolManagerFactory.class) {
            locator.setProtocolManagerFactory(new HandshakeProtocolManagerFactory());
        }
    }

    private static final class HandshakeProtocolManagerFactory
            extends ActiveMQClientProtocolManagerFactory {
        @Override
        public ClientProtocolManager newProtocolManager() {
            return new HandshakeProtocolManager();
        }
    }

    private static final class HandshakeProtocolManager extends ActiveMQClientProtocolManager {
        private BooleanSupplier hasOutgoingInterceptors = () -> false;

        @Override
        public RemotingConnection connect(
                Connection transport,
                long callTimeout,
                long failoverTimeout,
                List<Interceptor> incoming,
                List<Interceptor> outgoing,
                TopologyResponseHandler topology) {
            // Query the provider-owned list at handshake time: interceptors can be added
            // between connect() and sendConnect(), including during later reconnections.
            hasOutgoingInterceptors = () -> outgoing != null && !outgoing.isEmpty();
            return super.connect(
                    transport, callTimeout, failoverTimeout, incoming, outgoing, topology);
        }

        @Override
        public boolean sendConnect(String nodeID, String user, String password)
                throws ActiveMQException {
            var current = getCurrentConnection();
            if (current == null) {
                return super.sendConnect(nodeID, user, password);
            }
            var transport = current.getTransportConnection();
            boolean customOutgoing = hasOutgoingInterceptors.getAsBoolean();
            // Preserve the provider's interceptor rejection before checking transport state.
            if (!customOutgoing && !transport.isOpen()) {
                throw closedTransport();
            }
            if (customOutgoing || !(transport instanceof NettyConnection netty)) {
                return super.sendConnect(nodeID, user, password);
            }

            var channel = getChannel1();
            var closeFuture = netty.getChannel().closeFuture();
            ChannelFutureListener onClose = ignored -> channel.returnBlocking(closedTransport());
            var lock = channel.getLock();
            // The factory can miss a close before publishing its new connection. Also guard
            // a close between the check and the RPC wait. Holding the channel's reentrant
            // lock prevents returnBlocking() from being lost before sendBlocking() resets its
            // response; its Condition.await releases all holds while waiting for the reply.
            lock.lock();
            try {
                closeFuture.addListener(onClose);
                if (!transport.isOpen()) {
                    throw closedTransport();
                }
                return super.sendConnect(nodeID, user, password);
            } finally {
                closeFuture.removeListener(onClose);
                lock.unlock();
            }
        }

        private static ActiveMQNotConnectedException closedTransport() {
            return new ActiveMQNotConnectedException(
                    "Transport closed during the Artemis CONNECT handshake");
        }
    }

    @Override
    public ConnectionFactory createOutbound(JmsProperties.Target target) {
        var factory = (ActiveMQConnectionFactory) create(target);
        long timeoutMs = target.getTimeoutSeconds() * 1000L;
        factory.setCallTimeout(bounded(factory.getCallTimeout(), timeoutMs));
        factory.setCallFailoverTimeout(bounded(factory.getCallFailoverTimeout(), timeoutMs));
        // The forwarder retires a failed client and reconnects on the next request.
        // Provider retry loops must not retain the source listener indefinitely.
        factory.setInitialConnectAttempts(0);
        factory.setReconnectAttempts(0);
        var connectors = factory.getServerLocator().getStaticTransportConfigurations();
        if (connectors != null) {
            for (var connector : connectors) {
                var params = connector.getParams();
                Object existing = params.get(TransportConstants.NETTY_CONNECT_TIMEOUT);
                long configured =
                        existing == null ? timeoutMs : Long.parseLong(existing.toString());
                params.put(
                        TransportConstants.NETTY_CONNECT_TIMEOUT,
                        (int) bounded(configured, timeoutMs));
            }
        }
        return factory;
    }

    private static long bounded(long configured, long maximum) {
        return configured > 0 ? Math.min(configured, maximum) : maximum;
    }
}
