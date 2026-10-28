package com.echo.jms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.echo.config.JmsProperties;
import com.echo.jms.target.ArtemisFactoryProvider;

import jakarta.jms.JMSException;

import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.core.protocol.core.impl.PacketImpl;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ArtemisFailedConnectCleanupIntegrationTest {
    @Test
    @Timeout(30)
    void failedHandshakeCancelsHeartbeatEvenAfterTransportReferenceIsCleared() throws Exception {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        var connectPackets = new AtomicInteger();
        var protocolError = new AtomicReference<Exception>();
        // Close only after observing an actual CONNECT frame on the wire.
        try (var peer = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            var acceptor =
                    new Thread(
                            () -> {
                                while (!peer.isClosed()) {
                                    try (var socket = peer.accept()) {
                                        socket.setSoTimeout(2000);
                                        var input = new DataInputStream(socket.getInputStream());
                                        if (!Arrays.equals(
                                                input.readNBytes(7),
                                                "ARTEMIS".getBytes(StandardCharsets.US_ASCII))) {
                                            throw new IOException(
                                                    "Unexpected client protocol preamble");
                                        }
                                        while (true) {
                                            int length = input.readInt();
                                            if (length < 9 || length > 65536) {
                                                throw new IOException(
                                                        "Unexpected client packet length: "
                                                                + length);
                                            }
                                            byte type = input.readByte();
                                            input.skipNBytes(length - 1L);
                                            if (type == PacketImpl.CONNECT) {
                                                connectPackets.incrementAndGet();
                                                break;
                                            }
                                        }
                                    } catch (Exception failure) {
                                        if (!peer.isClosed()) {
                                            protocolError.compareAndSet(null, failure);
                                        }
                                    }
                                }
                            },
                            "failed-handshake-peer");
            acceptor.setDaemon(true);
            acceptor.start();
            try {
                var target = new JmsProperties.Target();
                target.setType("artemis");
                target.setUsername("admin");
                target.setPassword("admin");
                target.setServerUrl(
                        "tcp://127.0.0.1:"
                                + peer.getLocalPort()
                                + "?callTimeout=500&initialConnectAttempts=0&reconnectAttempts=0");
                try (var factory =
                        (ActiveMQConnectionFactory) new ArtemisFactoryProvider().create(target)) {
                    assertThat(
                                    factory.getServerLocator()
                                            .setThreadPools(
                                                    ActiveMQClient.getGlobalThreadPool(),
                                                    scheduler,
                                                    ActiveMQClient
                                                            .getGlobalFlowControlThreadPool()))
                            .isTrue();
                    for (int attempt = 0; attempt < 5; attempt++) {
                        assertThatThrownBy(factory::createConnection)
                                .isInstanceOf(JMSException.class);
                        assertThat(protocolError.get()).isNull();
                        assertThat(connectPackets.get()).isEqualTo(attempt + 1);
                        await().atMost(Duration.ofSeconds(2))
                                .untilAsserted(
                                        () ->
                                                assertThat(scheduler.getQueue())
                                                        .as(
                                                                "a failed session factory must release its heartbeat")
                                                        .isEmpty());
                    }
                }
                assertThat(scheduler.getQueue()).isEmpty();
            } finally {
                peer.close();
                acceptor.join(5000);
                assertThat(acceptor.isAlive()).isFalse();
            }
        } finally {
            scheduler.shutdownNow();
            assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
