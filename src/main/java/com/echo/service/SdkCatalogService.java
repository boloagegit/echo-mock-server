package com.echo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Immutable lookup of build-time SDK releases; request paths never become filesystem paths. */
@Service
public final class SdkCatalogService {
    private static final String ROOT = "echo-sdk/";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,254}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final byte[] catalogBytes;
    private final Map<Key, Download> downloads;
    private final Function<String, Resource> resources;

    @Autowired
    public SdkCatalogService(ObjectMapper objectMapper) throws IOException {
        this(objectMapper, new ClassPathResource(ROOT + "catalog.json"), ClassPathResource::new);
    }

    SdkCatalogService(ObjectMapper objectMapper, Resource catalog,
                      Function<String, Resource> resources) throws IOException {
        this.resources = resources;
        try (var input = catalog.getInputStream()) {
            catalogBytes = input.readAllBytes();
        }
        JsonNode root = objectMapper.readTree(catalogBytes);
        if (root == null || root.path("schemaVersion").asInt(-1) != 1) {
            throw new IllegalStateException("Invalid SDK catalog: unsupported schema");
        }
        Map<Key, Download> registered = new HashMap<>();
        var sdkIds = new HashSet<String>();
        for (JsonNode sdk : array(root, "sdks")) {
            String sdkId = token(sdk, "id", ID);
            require(sdkIds.add(sdkId), "duplicate SDK: " + sdkId);
            String recommended = token(sdk, "recommendedVersion", VERSION);
            var versions = new HashSet<String>();
            for (JsonNode release : array(sdk, "releases")) {
                String version = token(release, "version", VERSION);
                require(versions.add(version), "duplicate release: " + sdkId + "/" + version);
                var fileNames = new HashSet<String>();
                JsonNode artifacts = array(release, "artifacts");
                require(!artifacts.isEmpty(), "release has no artifacts");
                for (JsonNode artifact : artifacts) {
                    String artifactId = token(artifact, "id", ID);
                    String fileName = token(artifact, "fileName", FILE_NAME);
                    require(fileNames.add(fileName), "duplicate filename in release");
                    String sha256 = token(artifact, "sha256", SHA256);
                    String mediaType = text(artifact, "mediaType");
                    MediaType.parseMediaType(mediaType);
                    require(artifact.path("size").canConvertToLong() && artifact.path("size").asLong() >= 0,
                            "invalid artifact size");
                    long size = artifact.path("size").asLong();
                    String expectedUrl = "/api/admin/sdk/" + sdkId + "/releases/"
                            + URLEncoder.encode(version, StandardCharsets.UTF_8) + "/artifacts/" + artifactId;
                    require(expectedUrl.equals(text(artifact, "downloadUrl")), "inconsistent download URL");
                    String resourcePath = ROOT + sdkId + "/" + version + "/" + fileName;
                    Resource resource = resources.apply(resourcePath);
                    require(resource.exists() && resource.contentLength() == size,
                            "missing artifact or size mismatch: " + resourcePath);
                    Key key = new Key(sdkId, version, artifactId);
                    require(!registered.containsKey(key), "duplicate artifact id");
                    registered.put(key, new Download(fileName, size, sha256, mediaType, resourcePath));
                }
            }
            require(versions.contains(recommended), "recommended version is missing: " + sdkId);
        }
        downloads = Map.copyOf(registered);
    }

    public Resource catalog() {
        return new ByteArrayResource(catalogBytes.clone());
    }

    public Optional<Download> find(String sdkId, String version, String artifactId) {
        if (!matches(ID, sdkId) || !matches(VERSION, version) || !matches(ID, artifactId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(downloads.get(new Key(sdkId, version, artifactId)));
    }

    public Resource resource(Download download) {
        return resources.apply(download.resourcePath());
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static String token(JsonNode node, String field, Pattern pattern) {
        String value = text(node, field);
        require(matches(pattern, value), "invalid " + field);
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Invalid SDK catalog: missing " + field);
        }
        return value.asText();
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException("Invalid SDK catalog: invalid " + field);
        }
        return value;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException("Invalid SDK catalog: " + message);
    }

    private record Key(String sdkId, String version, String artifactId) {}

    public record Download(String fileName, long size, String sha256, String mediaType, String resourcePath) {}
}
