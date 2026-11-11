package com.echo.service;

import com.echo.config.HttpTlsPolicy;
import com.echo.config.WebMvcConfig;
import com.echo.entity.Protocol;
import com.echo.pipeline.MockRequest;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpTlsPolicyIntegrationTest {
    private static final String PASSWORD = "echo-test-only";
    @TempDir static Path directory;
    private static KeyStore serverKeys;
    private static KeyStore trustedCertificates;
    private static Path trustFile;
    private HttpsServer server;

    @BeforeAll
    static void certificate() throws Exception {
        Path keys = directory.resolve("server keys.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "owned",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", keys.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Test certificate generation timed out");
        }
        assertThat(process.exitValue()).isZero();
        serverKeys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keys)) { serverKeys.load(input, PASSWORD.toCharArray()); }
        trustedCertificates = KeyStore.getInstance("PKCS12");
        trustedCertificates.load(null, null);
        trustedCertificates.setCertificateEntry("owned", serverKeys.getCertificate("owned"));
        trustFile = directory.resolve("http trust certificates.p12");
        try (var output = Files.newOutputStream(trustFile)) { trustedCertificates.store(output, PASSWORD.toCharArray()); }
    }

    @BeforeEach
    void start() throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(serverKeys, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            byte[] body = "tls-policy-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", url("127.0.0.1", "/"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void savedProfilesAndBothLegacyClientsHonorTheirOwnVerificationFlags(boolean legacyStrict, boolean privateCa) throws Exception {
        HttpTlsPolicy policy = policy(legacyStrict, privateCa);
        var catalog = mock(HttpTargetConnectionService.class);
        var forwarder = forwarder(catalog, policy);
        var rest = new WebMvcConfig(policy).restTemplate();
        try {
            for (String host : List.of("localhost", "127.0.0.1")) {
                boolean valid = privateCa && host.equals("localhost");
                for (boolean profileStrict : List.of(false, true)) {
                    var target = new HttpTargetConnectionService.ResolvedTarget(1L, profileStrict ? 2L : 1L,
                            "Owned", url(host, ""), "NONE", null, null, 3, 5, profileStrict);
                    when(catalog.resolveEnabled(1L)).thenReturn(target);
                    var response = forwarder.forward(request(host, "/"), 1L, false);
                    assertThat(response.getStatus()).isEqualTo(!profileStrict || valid ? 200 : 502);
                    if (!profileStrict || valid) assertThat(response.getBody()).isEqualTo("tls-policy-ok");
                }
                var legacy = forwarder.forwardOriginalHost(request(host, "/"), false);
                assertThat(legacy.getStatus()).isEqualTo(!legacyStrict || valid ? 200 : 502);
                if (!legacyStrict || valid) {
                    assertThat(legacy.getBody()).isEqualTo("tls-policy-ok");
                    assertThat(rest.getForObject(url(host, "/"), String.class)).isEqualTo("tls-policy-ok");
                } else {
                    assertThatThrownBy(() -> rest.getForObject(url(host, "/"), String.class))
                            .isInstanceOf(ResourceAccessException.class);
                }
            }
        } finally {
            forwarder.closeClients();
            ((HttpComponentsClientHttpRequestFactory) rest.getRequestFactory()).destroy();
        }
    }

    @Test
    void redirectsKeepCertificateAndHostnameVerification() throws Exception {
        var catalog = mock(HttpTargetConnectionService.class);
        when(catalog.resolveEnabled(1L)).thenReturn(new HttpTargetConnectionService.ResolvedTarget(
                1L, 1L, "Owned", url("localhost", ""), "NONE", null, null, 3, 5, true));
        var policy = policy(true, true);
        var forwarder = forwarder(catalog, policy);
        var rest = new WebMvcConfig(policy).restTemplate();
        try {
            assertThat(forwarder.forward(request("localhost", "/redirect"), 1L, false).getStatus()).isEqualTo(502);
            assertThat(forwarder.forwardOriginalHost(request("localhost", "/redirect"), false).getStatus()).isEqualTo(502);
            assertThatThrownBy(() -> rest.getForObject(url("localhost", "/redirect"), String.class))
                    .isInstanceOf(ResourceAccessException.class);
        } finally {
            forwarder.closeClients();
            ((HttpComponentsClientHttpRequestFactory) rest.getRequestFactory()).destroy();
        }
    }

    @Test
    void privateHttpTrustDoesNotChangeJvmDefaults() throws Exception {
        String originalProperty = System.getProperty("javax.net.ssl.trustStore");
        SSLContext originalContext = SSLContext.getDefault();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var request = HttpRequest.newBuilder(URI.create(url("localhost", "/"))).timeout(Duration.ofSeconds(5)).build();
        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);
        var forwarder = forwarder(mock(HttpTargetConnectionService.class), policy(true, true));
        try {
            assertThat(forwarder.forwardOriginalHost(request("localhost", "/"), false).getBody()).isEqualTo("tls-policy-ok");
        } finally { forwarder.closeClients(); }
        assertThat(SSLContext.getDefault()).isSameAs(originalContext);
        assertThat(System.getProperty("javax.net.ssl.trustStore")).isEqualTo(originalProperty);
        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);
    }

    @Test
    void bootBindsNamedTrustStoreFromAPathWithSpaces() {
        context().withPropertyValues("echo.http.tls.legacy-verification-enabled=true",
                "echo.http.tls.trust-bundle=private-http",
                "spring.ssl.bundle.jks.private-http.truststore.location=" + trustFile.toUri(),
                "spring.ssl.bundle.jks.private-http.truststore.password=" + PASSWORD,
                "spring.ssl.bundle.jks.private-http.truststore.type=PKCS12")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(HttpTlsPolicy.class).legacyVerificationEnabled()).isTrue();
                    assertThat(ctx.getBean(HttpTlsPolicy.class).nettyContext(true)).isNotNull();
                });
    }

    @Test
    void defaultConfigurationRemainsCompatible() {
        context().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var policy = ctx.getBean(HttpTlsPolicy.class);
            assertThat(policy.legacyVerificationEnabled()).isFalse();
            assertThat(policy.nettyContext(true)).isNull();
        });
    }

    @Test
    void missingBundleFailsRatherThanSilentlyTrustingSomethingElse() {
        context().withPropertyValues("echo.http.tls.trust-bundle=missing")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void aKeyOnlyBundleCannotBecomeAnImplicitHttpClientIdentity() {
        var registry = new DefaultSslBundleRegistry();
        registry.registerBundle("key-only", SslBundle.of(SslStoreBundle.of(serverKeys, PASSWORD, null)));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("bundles", registry);
        assertThatThrownBy(() -> new HttpTlsPolicy(true, "key-only", beans.getBeanProvider(SslBundles.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("HTTP_TLS_TRUST_BUNDLE_REQUIRES_TRUST_STORE");
    }

    private static ApplicationContextRunner context() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class))
                .withUserConfiguration(HttpTlsPolicy.class);
    }

    private static HttpTlsPolicy policy(boolean strict, boolean privateCa) {
        var beans = new DefaultListableBeanFactory();
        var registry = new DefaultSslBundleRegistry();
        registry.registerBundle("owned", SslBundle.of(SslStoreBundle.of(null, null, trustedCertificates)));
        beans.registerSingleton("bundles", registry);
        return new HttpTlsPolicy(strict, privateCa ? "owned" : "", beans.getBeanProvider(SslBundles.class));
    }

    private static HttpOutboundForwarder forwarder(HttpTargetConnectionService catalog, HttpTlsPolicy policy) {
        return new HttpOutboundForwarder(catalog, 0, 50, 50, 10 * 1024 * 1024, 128 * 1024 * 1024,
                1000, 3, 5, 0, 30, 8, policy);
    }

    private MockRequest request(String host, String path) {
        return MockRequest.builder().protocol(Protocol.HTTP).method("GET").path(path)
                .targetHost(host + ":" + server.getAddress().getPort()).headers(Map.of()).build();
    }

    private String url(String host, String path) {
        return "https://" + host + ":" + server.getAddress().getPort() + path;
    }
}
