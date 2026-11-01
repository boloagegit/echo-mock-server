package com.echo.service;

import com.echo.entity.Protocol;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** X-Original-Host policy against a local synthetic HTTPS server only. */
class OriginalHostForwardingPolicyTest {

    @TempDir
    Path tempDirectory;

    private HttpsServer server;
    private HttpTargetConnectionService connectionService;
    private HttpOutboundForwarder forwarder;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpsServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(syntheticTlsContext()));
        server.createContext("/start", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Location", "/landed");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/landed", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            ok(exchange);
        });
        server.start();
        connectionService = mock(HttpTargetConnectionService.class);
        forwarder = new HttpOutboundForwarder(connectionService);
    }

    @AfterEach
    void tearDown() {
        forwarder.closeClients();
        server.stop(0);
    }

    @Test
    void legacyDefaultForwardsAnyHostAndFollowsRedirects() {
        MockResponse response = forwarder.forwardOriginalHost(request(authority()), true);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("landed");
        assertThat(paths).containsExactly("/start", "/landed");
    }

    @Test
    void allowlistForwardsAllowedHostButReturnsRedirectInsteadOfFollowingIt() {
        forwarder.setOriginalHostPolicy(new OriginalHostPolicy(OriginalHostPolicy.Mode.ALLOWLIST,
                Set.of("127.0.0.1:" + port())));

        MockResponse response = forwarder.forwardOriginalHost(request(authority()), true);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeaders()).containsEntry("Location", "/landed");
        assertThat(response.isForwarded()).isTrue();
        assertThat(paths).containsExactly("/start");
    }

    @Test
    void allowlistRejectsOtherHostsWithoutContactingThem() {
        forwarder.setOriginalHostPolicy(new OriginalHostPolicy(OriginalHostPolicy.Mode.ALLOWLIST,
                Set.of("api.example.com:443")));

        MockResponse response = forwarder.forwardOriginalHost(request(authority()), false);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getProxyError()).contains("allowed host list");
        assertThat(response.isMatched()).isFalse();
        assertThat(paths).isEmpty();
    }

    @Test
    void allowlistRejectsUserinfoTricksPointingAtAnAllowedHost() {
        forwarder.setOriginalHostPolicy(new OriginalHostPolicy(OriginalHostPolicy.Mode.ALLOWLIST,
                Set.of("127.0.0.1:" + port())));

        MockResponse response = forwarder.forwardOriginalHost(
                request("127.0.0.1:" + port() + "@127.0.0.1:" + port()), true);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(paths).isEmpty();
    }

    @Test
    void disabledRejectsEveryOriginalHostForward() {
        forwarder.setOriginalHostPolicy(new OriginalHostPolicy(OriginalHostPolicy.Mode.DISABLED, Set.of()));

        MockResponse response = forwarder.forwardOriginalHost(request(authority()), true);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getProxyError()).contains("disabled");
        assertThat(response.isMatched()).isTrue();
        assertThat(paths).isEmpty();
    }

    @Test
    void savedConnectionsKeepFollowingRedirectsUnderARestrictedPolicy() {
        forwarder.setOriginalHostPolicy(new OriginalHostPolicy(OriginalHostPolicy.Mode.DISABLED, Set.of()));
        when(connectionService.resolveEnabled(1L)).thenReturn(new HttpTargetConnectionService.ResolvedTarget(
                1L, 1L, "Profile", "https://" + authority(), "NONE", null, null, 5, 30, false));

        MockResponse response = forwarder.forward(request(null), 1L, false);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(paths).containsExactly("/start", "/landed");
    }

    private MockRequest request(String targetHost) {
        return MockRequest.builder().protocol(Protocol.HTTP).method("GET").path("/start")
                .headers(Map.of()).targetHost(targetHost).build();
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private String authority() {
        return "127.0.0.1:" + port();
    }

    private static void ok(HttpExchange exchange) throws IOException {
        byte[] body = "landed".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private SSLContext syntheticTlsContext() throws Exception {
        Path keyStorePath = tempDirectory.resolve("synthetic-loopback.p12");
        String password = "synthetic-test-only";
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "loopback", "-keyalg", "RSA", "-keystore", keyStorePath.toString(),
                "-storetype", "PKCS12", "-storepass", password, "-keypass", password,
                "-dname", "CN=localhost", "-validity", "1", "-noprompt").redirectErrorStream(true).start();
        try {
            assertThat(keytool.waitFor(10, TimeUnit.SECONDS)).as("synthetic certificate generation finished").isTrue();
            assertThat(keytool.exitValue()).isZero();
        } finally {
            keytool.destroyForcibly();
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) {
            store.load(input, password.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }
}
