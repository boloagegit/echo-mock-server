package com.echo.config;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Exercises the real HTTP parser without a proxy or servlet mock. */
class UndertowHeaderSecurityTest {
    private Undertow server;
    private int port;
    private final AtomicInteger handled = new AtomicInteger();
    @BeforeEach void start() {
        server = Undertow.builder().setIoThreads(1).setWorkerThreads(2)
                .addHttpListener(0, "127.0.0.1")
                .setHandler(exchange -> {
                    handled.incrementAndGet();
                    exchange.getResponseHeaders().put(Headers.CONNECTION, "close");
                    exchange.getResponseSender().send("ok");
                }).build();
        server.start();
        port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
    }
    @AfterEach void stop() { if (server != null) server.stop(); }
    @Test void normalRequestStillWorks() throws Exception {
        assertThat(send("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
                .startsWith("HTTP/1.1 200").endsWith("ok");
        assertThat(handled.get()).isEqualTo(1);
    }
    @Test void leadingWhitespaceOnFirstHeaderIsRejected() throws Exception {
        String response = send("GET / HTTP/1.1\r\n Host: localhost\r\nConnection: close\r\n\r\n");
        assertThat(handled.get()).isZero();
        assertThat(response).doesNotContain("200 OK");
    }
    @Test void spacesInHeaderNamesAreRejected() throws Exception {
        String response = send("GET / HTTP/1.1\r\nHost: localhost\r\nX-Audit Bad: x\r\nConnection: close\r\n\r\n");
        assertThat(handled.get()).isZero();
        assertThat(response).doesNotContain("200 OK");
    }
    @Test void carriageReturnOnlyHeaderTerminatorIsRejected() throws Exception {
        String response = send("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nX-Audit: x\r\r\r");
        assertThat(handled.get()).isZero();
        assertThat(response).doesNotContain("200 OK");
    }
    private String send(String request) throws Exception {
        try (Socket socket = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
