package com.echo.integration.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NativeHttpTlsIntegrationTest {
    private static final String TEST_PASSWORD = "echo-test-only";
    private static final Path KEYSTORE = createKeyStore();
    private static final SSLContext TRUSTED = trustTestCertificate();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry meters;
    @Autowired org.springframework.boot.web.servlet.server.ServletWebServerFactory serverFactory;

    @DynamicPropertySource
    static void tlsProperties(DynamicPropertyRegistry properties) {
        properties.add("echo.http.transport", () -> "native");
        properties.add("echo.jms.enabled", () -> false);
        properties.add("server.ssl.enabled", () -> true);
        properties.add("server.ssl.key-store", () -> KEYSTORE.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> TEST_PASSWORD);
        properties.add("server.ssl.key-store-type", () -> "PKCS12");
        properties.add("server.ssl.enabled-protocols", () -> "TLSv1.2,TLSv1.3");
        properties.add("server.http2.enabled", () -> true);
        properties.add("server.compression.enabled", () -> true);
        properties.add("server.compression.min-response-size", () -> 128);
    }

    @AfterAll
    static void removeTestCertificate() throws Exception {
        Files.deleteIfExists(KEYSTORE);
        Files.deleteIfExists(KEYSTORE.getParent().resolve("keytool.log"));
        Files.deleteIfExists(KEYSTORE.getParent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void verifiedTlsRetainsNativeMockAndProtectedAdministration(String protocol) throws Exception {
        HttpClient client = client(protocol, HttpClient.Version.HTTP_1_1);
        createRule(client, "/tls-" + protocol, "{\"value\":\"測試\"}");
        double before = nativeRequests();
        var reply = client.send(request("/mock/tls-" + protocol).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo("{\"value\":\"測試\"}");
        assertThat(reply.sslSession().orElseThrow().getProtocol()).isEqualTo(protocol);
        assertThat(nativeRequests()).isEqualTo(before + 1);
        var protectedApi = client.send(request("/api/admin/rules/all").DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(protectedApi.statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 100000})
    void tlsCompressionKeepsTheCompleteUnicodeBody(int repetitions) throws Exception {
        HttpClient client = client("TLSv1.3", HttpClient.Version.HTTP_1_1);
        String body = "有內容的回應".repeat(repetitions);
        createRule(client, "/tls-compression", body);
        double before = nativeRequests();
        var response = client.send(request("/mock/tls-compression").header("Accept-Encoding", "gzip").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
            assertThat(new String(gzip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(body);
        }
        assertThat(nativeRequests()).isEqualTo(before + 1);
    }

    @Test
    void http2RetainsServletStreamHandlingOnTheSameTlsServer() throws Exception {
        assertThat(((org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory) serverFactory)
                .getHttp2().isEnabled()).isTrue();
        // Undertow's ALPN compatibility check requires the TLS 1.2 HTTP/2 cipher in
        // ClientHello even when TLS 1.3 is ultimately negotiated.
        HttpClient client = client("TLSv1.3,TLSv1.2", HttpClient.Version.HTTP_2);
        createRule(client, "/tls-http2", "h2-correct");
        double before = nativeRequests();
        var response = client.send(request("/mock/tls-http2").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(response.sslSession().orElseThrow().getProtocol()).isEqualTo("TLSv1.3");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("h2-correct");
        assertThat(nativeRequests()).isEqualTo(before);
    }

    @Test
    void untrustedCertificateIsNotSilentlyAccepted() {
        assertThatThrownBy(() -> HttpClient.newHttpClient().send(request("/api/admin/status").build(),
                HttpResponse.BodyHandlers.ofString())).isInstanceOf(javax.net.ssl.SSLHandshakeException.class);
    }

    private HttpClient client(String protocol, HttpClient.Version version) {
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(protocol.split(","));
        return HttpClient.newBuilder().sslContext(TRUSTED).sslParameters(parameters).version(version)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("https://localhost:" + port + path)).timeout(Duration.ofSeconds(5));
    }

    private void createRule(HttpClient client, String path, String body) throws Exception {
        var request = request("/api/admin/rules").header("Authorization", "Basic YWRtaW46YWRtaW4=")
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "protocol", "HTTP", "matchKey", path, "method", "GET", "status", 200, "responseBody", body)))).build();
        var reply = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(201);
    }

    private double nativeRequests() {
        var counter = meters.find("echo.http.native.requests").tag("mode", "native").counter();
        return counter == null ? 0 : counter.count();
    }

    private static Path createKeyStore() {
        try {
            Path dir = Files.createTempDirectory("echo test tls ");
            Path store = dir.resolve("server.p12");
            String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
            Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
            Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "echo-test",
                    "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", TEST_PASSWORD,
                    "-keypass", TEST_PASSWORD, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1", "-noprompt")
                    .redirectErrorStream(true).redirectOutput(dir.resolve("keytool.log").toFile()).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Test certificate generation timed out");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("Test certificate generation failed");
            return store;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static SSLContext trustTestCertificate() {
        try (var input = Files.newInputStream(KEYSTORE)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(input, TEST_PASSWORD.toCharArray());
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
