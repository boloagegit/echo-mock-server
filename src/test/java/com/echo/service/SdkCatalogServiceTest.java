package com.echo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkCatalogServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, byte[]> resources = new HashMap<>();
    private ObjectNode catalog;
    private ObjectNode firstSdk;

    @BeforeEach
    void prepare() throws Exception {
        catalog = mapper.createObjectNode().put("schemaVersion", 1);
        catalog.putArray("sdks");
        firstSdk = sdk("alpha", "2.0.0");
        release(firstSdk, "2.0.0", "alpha-new.txt", "new alpha");
        release(firstSdk, "1.0.0", "alpha-old.txt", "old alpha");
        release(sdk("beta", "1.0.0"), "1.0.0", "beta.txt", "different SDK");
    }

    @Test
    void resolvesMultipleSdksAndOlderVersionsWithoutCrossingTheirFiles() throws Exception {
        var service = service();
        var older = service.find("alpha", "1.0.0", "package").orElseThrow();
        var beta = service.find("beta", "1.0.0", "package").orElseThrow();
        assertThat(older.fileName()).isEqualTo("alpha-old.txt");
        assertThat(new String(service.resource(older).getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("old alpha");
        assertThat(new String(service.resource(beta).getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("different SDK");
        assertThat(older.mediaType()).isEqualTo("text/plain");
    }

    @Test
    void unknownAndUnsafeCoordinatesDoNotFallBackToAnotherRelease() throws Exception {
        var service = service();
        assertThat(service.find("missing", "1.0.0", "package")).isEmpty();
        assertThat(service.find("alpha", "9.0.0", "package")).isEmpty();
        assertThat(service.find("alpha", "1.0.0", "missing")).isEmpty();
        assertThat(service.find("../alpha", "1.0.0", "package")).isEmpty();
        assertThat(service.find("alpha", "../1.0.0", "package")).isEmpty();
        assertThat(service.find("alpha", "1.0.0", "../package")).isEmpty();
        assertThat(service.find(null, "1.0.0", "package")).isEmpty();
    }

    @Test
    void duplicateVersionsAreRejected() {
        ((ArrayNode) firstSdk.get("releases")).add(firstSdk.get("releases").get(0).deepCopy());
        assertThatThrownBy(this::service).isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate release");
    }

    @Test
    void missingRecommendedVersionIsRejected() {
        firstSdk.put("recommendedVersion", "3.0.0");
        assertThatThrownBy(this::service).isInstanceOf(IllegalStateException.class).hasMessageContaining("recommended version");
    }

    @Test
    void missingOrWrongSizedArtifactIsRejected() {
        resources.remove("echo-sdk/alpha/1.0.0/alpha-old.txt");
        assertThatThrownBy(this::service).isInstanceOf(IllegalStateException.class).hasMessageContaining("size mismatch");
    }

    @Test
    void unsafeArtifactFilenameIsRejected() {
        ((ObjectNode) firstSdk.get("releases").get(0).get("artifacts").get(0)).put("fileName", "../other.txt");
        assertThatThrownBy(this::service).isInstanceOf(IllegalStateException.class).hasMessageContaining("fileName");
    }

    @Test
    void catalogResponseDoesNotExposeTheStoredBytes() throws Exception {
        var service = service();
        byte[] exposed = service.catalog().getInputStream().readAllBytes();
        exposed[0] = '!';
        assertThat(mapper.readTree(service.catalog().getInputStream()).path("sdks")).hasSize(2);
    }

    @Test
    void anEmptyCatalogRemainsUsable() throws Exception {
        catalog.set("sdks", mapper.createArrayNode());
        var service = service();
        assertThat(mapper.readTree(service.catalog().getInputStream()).path("sdks")).isEmpty();
        assertThat(service.find("alpha", "1.0.0", "package")).isEmpty();
    }

    private SdkCatalogService service() throws Exception {
        return new SdkCatalogService(mapper, new ByteArrayResource(mapper.writeValueAsBytes(catalog)),
                path -> new ByteArrayResource(resources.getOrDefault(path, new byte[0])));
    }

    private ObjectNode sdk(String id, String recommended) {
        ObjectNode node = mapper.createObjectNode().put("id", id).put("recommendedVersion", recommended);
        node.putArray("releases");
        ((ArrayNode) catalog.get("sdks")).add(node);
        return node;
    }

    private void release(ObjectNode sdk, String version, String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String id = sdk.path("id").asText();
        resources.put("echo-sdk/" + id + "/" + version + "/" + fileName, bytes);
        ObjectNode release = mapper.createObjectNode().put("version", version);
        ObjectNode artifact = mapper.createObjectNode().put("id", "package").put("fileName", fileName)
                .put("size", bytes.length).put("mediaType", "text/plain")
                .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .put("downloadUrl", "/api/admin/sdk/" + id + "/releases/" + version + "/artifacts/package");
        release.putArray("artifacts").add(artifact);
        ((ArrayNode) sdk.get("releases")).add(release);
    }
}
