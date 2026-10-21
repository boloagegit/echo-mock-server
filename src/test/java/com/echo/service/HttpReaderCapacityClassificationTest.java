package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.pipeline.MockRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpReaderCapacityClassificationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localReaderCapacityIs503AndDoesNotPreventRecovery(boolean declaredLength) throws Exception {
        var downstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        byte[] body = new byte[32768];
        downstream.createContext("/", exchange -> {
            byte[] response = exchange.getRequestURI().getPath().equals("/ok") ? new byte[]{'o', 'k'} : body;
            exchange.sendResponseHeaders(200, declaredLength ? response.length : 0);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            finally { exchange.close(); }
        });
        downstream.start();
        var connections = mock(HttpTargetConnectionService.class);
        var forwarder = new HttpOutboundForwarder(connections, 0, 10, 10, 65536, 8192, 500, 2, 2, 0, 30);
        when(connections.resolveEnabled(1L)).thenReturn(new HttpTargetConnectionService.ResolvedTarget(
                1L, 0L, "Reader capacity", "http://localhost:" + downstream.getAddress().getPort(),
                "NONE", null, null, 2, 2, false));
        try {
            var rejected = forwarder.forward(MockRequest.builder().protocol(Protocol.HTTP)
                    .method("GET").path("/large").headers(Map.of()).build(), 1L, false);
            assertThat(rejected.getStatus()).isEqualTo(503);
            assertThat(rejected.getProxyError()).startsWith("HTTP_RESPONSE_BUFFER_CAPACITY_EXHAUSTED");
            assertThat(forwarder.resourceMetricsSnapshot()).containsEntry("bufferedBytes", 0L);
            var recovered = forwarder.forward(MockRequest.builder().protocol(Protocol.HTTP)
                    .method("GET").path("/ok").headers(Map.of()).build(), 1L, false);
            assertThat(recovered.getStatus()).isEqualTo(200);
            assertThat(recovered.getBody()).isEqualTo("ok");
        } finally { forwarder.closeClients(); downstream.stop(0); }
    }
}
