package com.echo.service;

import com.echo.repository.HttpRuleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "echo.jms.enabled=false", "echo.features.bulk-import-export-enabled=true",
        "echo.openapi.local-references-only=true"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiReferencePolicyHttpIntegrationTest {
    @TempDir static Path directory;
    @Autowired TestRestTemplate client;
    @Autowired HttpRuleRepository rules;
    private static HttpServer downstream;
    private static String remote;
    private static Path file;
    private static final AtomicInteger requests = new AtomicInteger();

    @BeforeAll
    static void fixture() throws Exception {
        byte[] schema = OpenApiPolicyFixtures.JSON.writeValueAsBytes(OpenApiPolicyFixtures.schema());
        file = directory.resolve("owned schema.json");
        Files.write(file, schema);
        downstream = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        downstream.createContext("/schema.json", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, schema.length);
            try (var output = exchange.getResponseBody()) { output.write(schema); }
        });
        downstream.start();
        remote = "http://127.0.0.1:" + downstream.getAddress().getPort() + "/schema.json";
    }

    @AfterAll
    static void cleanup() { if (downstream != null) downstream.stop(0); }

    @Test
    void authenticatedMultipartPreviewsPreserveLocalImportsAndRejectExternalAccessWithoutWrites() throws Exception {
        long originalCount = rules.count();
        for (String version : List.of("2.0", "3.0.3", "3.1.0")) {
            for (boolean yaml : List.of(false, true)) {
                var allowed = upload(OpenApiPolicyFixtures.document(version, "nested-ref", yaml, remote), true);
                assertThat(allowed.getStatusCode().value()).isEqualTo(200);
                assertThat(allowed.getBody().path("success").asBoolean()).isTrue();
                assertThat(allowed.getBody().path("rules").get(0).path("responseBody").asText())
                        .contains(OpenApiPolicyFixtures.MARKER);
                for (String uri : List.of(remote, file.toUri().toString())) {
                    var rejected = upload(OpenApiPolicyFixtures.document(version, "external", yaml, uri), true);
                    assertThat(rejected.getStatusCode().value()).isEqualTo(400);
                    assertThat(rejected.getBody().path("success").asBoolean()).isFalse();
                    assertThat(rejected.getBody().path("errors").toString()).contains(OpenApiLocalReferenceGuard.EXTERNAL_DISABLED);
                    assertThat(rejected.getBody().toString()).doesNotContain(OpenApiPolicyFixtures.MARKER);
                }
            }
        }
        assertThat(requests.get()).isZero();
        assertThat(rules.count()).isEqualTo(originalCount);
        assertThat(upload(OpenApiPolicyFixtures.document("3.1.0", "inline", false, remote), true)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anonymousImportsRemainProtectedBeforeParsing() throws Exception {
        var response = upload(OpenApiPolicyFixtures.document("3.1.0", "external", false, remote), false);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(requests.get()).isZero();
    }

    private ResponseEntity<JsonNode> upload(String document, boolean authenticated) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(document.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "owned-spec.yaml"; }
        });
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (authenticated) headers.setBasicAuth("admin", "admin");
        return client.exchange("/api/admin/rules/import-openapi/preview", HttpMethod.POST,
                new HttpEntity<>(form, headers), JsonNode.class);
    }
}
