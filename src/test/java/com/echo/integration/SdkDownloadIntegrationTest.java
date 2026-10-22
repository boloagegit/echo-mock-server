package com.echo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Validates the shipped SDK bytes, documentation and access through the real security chain. */
@SpringBootTest(properties = "echo.jms.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SdkDownloadIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anonymousDownloadsRequireLogin() throws Exception {
        for (String path : new String[]{"", "/spring-openfeign/releases/0.1.0-SNAPSHOT/artifacts/jar",
                "/spring-openfeign/releases/0.1.0-SNAPSHOT/artifacts/bundle"}) {
            mockMvc.perform(get("/api/admin/sdk" + path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCanDownloadAStandaloneStarterWithMatchingMetadata() throws Exception {
        JsonNode info = recommendedRelease();
        byte[] jar = download(artifact(info, "jar"), "application/java-archive");
        Map<String, byte[]> contents = unzip(jar);
        assertThat(contents).containsKey("com/echo/sdk/feign/EchoRoutingClient.class");
        assertThat(new String(contents.get(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"),
                StandardCharsets.UTF_8)).contains("com.echo.sdk.autoconfigure.EchoFeignAutoConfiguration");
        assertThat(contents.keySet()).noneMatch(name ->
                name.startsWith("BOOT-INF/") || name.startsWith("org/springframework/")
                        || name.startsWith("feign/") || name.startsWith("com/echo/controller/"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void zipContainsTheSameJarAndVersionedOfflineInstallationInstructions() throws Exception {
        JsonNode info = recommendedRelease();
        byte[] jar = download(artifact(info, "jar"), "application/java-archive");
        Map<String, byte[]> bundle = unzip(download(artifact(info, "bundle"), "application/zip"));
        assertThat(bundle.get(artifact(info, "jar").get("fileName").asText())).isEqualTo(jar);
        assertThat(bundle).containsKeys("README.md", "application-echo.yml.example",
                "echo-spring-boot-starter.pom", "SHA256SUMS", "LICENSE");
        assertThat(new String(bundle.get("README.md"), StandardCharsets.UTF_8))
                .contains(info.get("version").asText(), "mvn install:install-file", "ECHO_CLIENT_ENABLED=true")
                .doesNotContain("@sdkVersion@");
        assertThat(new String(bundle.get("echo-spring-boot-starter.pom"), StandardCharsets.UTF_8))
                .contains("<version>" + info.get("version").asText() + "</version>");
        assertThat(new String(bundle.get("application-echo.yml.example"), StandardCharsets.UTF_8))
                .contains("${ECHO_CLIENT_ENABLED:false}");
        for (String line : new String(bundle.get("SHA256SUMS"), StandardCharsets.UTF_8).lines().toList()) {
            String[] entry = line.split("  ", 2);
            assertThat(sha256(bundle.get(entry[1]))).isEqualTo(entry[0]);
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void unknownSdkVersionAndArtifactReturnNotFound() throws Exception {
        for (String path : new String[]{
                "/missing/releases/1.0.0/artifacts/jar",
                "/spring-openfeign/releases/missing/artifacts/jar",
                "/spring-openfeign/releases/0.1.0-SNAPSHOT/artifacts/missing"}) {
            mockMvc.perform(get("/api/admin/sdk" + path)).andExpect(status().isNotFound());
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void catalogDeclaresVersionedDownloadsAndPerReleaseGuides() throws Exception {
        JsonNode root = metadata();
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        for (JsonNode sdk : root.path("sdks")) {
            assertThat(sdk.path("id").asText()).isNotBlank();
            boolean recommendedExists = false;
            for (JsonNode release : sdk.path("releases")) {
                recommendedExists |= release.path("version").asText().equals(sdk.path("recommendedVersion").asText());
                assertThat(release.path("guide").path("sections").isArray()).isTrue();
                assertThat(release.path("guide").path("sections").isEmpty()).isFalse();
                for (JsonNode artifact : release.path("artifacts")) {
                    assertThat(artifact.path("downloadUrl").asText())
                            .startsWith("/api/admin/sdk/" + sdk.path("id").asText() + "/releases/");
                    download(artifact, artifact.path("mediaType").asText());
                }
            }
            assertThat(recommendedExists).isTrue();
        }
    }

    private JsonNode metadata() throws Exception {
        byte[] json = mockMvc.perform(get("/api/admin/sdk").accept("application/json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return objectMapper.readTree(json);
    }

    private JsonNode recommendedRelease() throws Exception {
        for (JsonNode sdk : metadata().path("sdks")) {
            if (!sdk.path("id").asText().equals("spring-openfeign")) continue;
            for (JsonNode release : sdk.path("releases")) {
                if (release.path("version").asText().equals(sdk.path("recommendedVersion").asText())) return release;
            }
        }
        throw new AssertionError("OpenFeign SDK recommended release missing");
    }

    private static JsonNode artifact(JsonNode release, String id) {
        for (JsonNode artifact : release.path("artifacts")) {
            if (artifact.path("id").asText().equals(id)) return artifact;
        }
        throw new AssertionError("Artifact missing: " + id);
    }

    private byte[] download(JsonNode artifact, String mediaType) throws Exception {
        byte[] bytes = mockMvc.perform(get(artifact.get("downloadUrl").asText()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", mediaType))
                .andExpect(header().string("Content-Disposition", containsString(artifact.get("fileName").asText())))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes).hasSize(artifact.get("size").asInt());
        assertThat(sha256(bytes)).isEqualTo(artifact.get("sha256").asText());
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory()) files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }
}
