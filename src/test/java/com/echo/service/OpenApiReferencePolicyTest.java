package com.echo.service;

import com.sun.net.httpserver.HttpServer;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiReferencePolicyTest {
    private static HttpServer server;
    private static Path ownedDirectory;
    private static Path schemaFile;
    private static final AtomicInteger requests = new AtomicInteger();

    @BeforeAll
    static void fixtures() throws Exception {
        // Keep relative-file fixtures on the checkout's drive on Windows as well.
        Files.createDirectories(Path.of("build", "tmp"));
        ownedDirectory = Files.createTempDirectory(Path.of("build", "tmp"), "openapi policy ").toAbsolutePath();
        schemaFile = ownedDirectory.resolve("owned-schema.json");
        byte[] schema = OpenApiPolicyFixtures.JSON.writeValueAsBytes(OpenApiPolicyFixtures.schema());
        Files.write(schemaFile, schema);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/schema.json", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, schema.length);
            try (var output = exchange.getResponseBody()) { output.write(schema); }
        });
        server.start();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (server != null) server.stop(0);
        if (ownedDirectory != null) {
            try (var files = Files.walk(ownedDirectory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    static Stream<Arguments> normalCases() {
        return Stream.of("2.0", "3.0.3", "3.1.0").flatMap(version ->
                Stream.of("inline", "array-ref", "nested-ref", "allof-ref", "response-ref", "example-ref",
                        "path-ref", "escaped-schema-name", "missing-local", "recursive-ref", "media-example",
                        "schema-example", "reserved-property", "metadata-url", "pointer-aliases")
                        .flatMap(kind -> Stream.of(false, true).map(yaml -> Arguments.of(version, kind, yaml))));
    }

    @ParameterizedTest(name = "{0} {1} yaml={2}")
    @MethodSource("normalCases")
    void localOnlyModePreservesOriginalRuleOutputAndLiteralData(String version, String kind, boolean yaml) throws Exception {
        String input = OpenApiPolicyFixtures.document(version, kind, yaml, remote());
        var original = service(false).parse(input);
        int before = requests.get();
        var protectedResult = service(true).parse(input);
        assertThat(requests.get()).isEqualTo(before);
        assertThat(protectedResult.isSuccess()).isEqualTo(original.isSuccess());
        com.fasterxml.jackson.databind.JsonNode protectedRules = OpenApiPolicyFixtures.JSON.valueToTree(protectedResult.getRules());
        assertThat(protectedRules)
                .isEqualTo(OpenApiPolicyFixtures.JSON.valueToTree(original.getRules()));
    }

    static Stream<Arguments> externalCases() {
        return Stream.of("2.0", "3.0.3", "3.1.0").flatMap(version ->
                Stream.of("http", "file", "absolute-path", "relative-path", "classpath")
                        .flatMap(kind -> Stream.of(false, true).map(yaml -> Arguments.of(version, kind, yaml))));
    }

    @ParameterizedTest(name = "{0} {1} yaml={2}")
    @MethodSource("externalCases")
    void externalDocumentsAreRejectedBeforeAnyResolution(String version, String kind, boolean yaml) throws Exception {
        String uri = switch (kind) {
            case "http" -> remote();
            case "file" -> schemaFile.toUri().toString();
            case "absolute-path" -> schemaFile.toString().replace('\\', '/');
            case "relative-path" -> "./" + Path.of("").toAbsolutePath().relativize(schemaFile).toString().replace('\\', '/');
            case "classpath" -> "classpath:/openapi-policy-owned.json";
            default -> throw new IllegalArgumentException(kind);
        };
        int before = requests.get();
        var result = service(true).parse(OpenApiPolicyFixtures.document(version, "external", yaml, uri));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).contains(OpenApiLocalReferenceGuard.EXTERNAL_DISABLED);
        assertThat(result.getRules()).isEmpty();
        assertThat(requests.get()).isEqualTo(before);
    }

    @Test
    void localPointersCannotTurnLiteralExampleDataIntoExternalReferences() throws Exception {
        for (boolean yaml : List.of(false, true)) {
            for (String uri : List.of(remote(), schemaFile.toUri().toString())) {
                int before = requests.get();
                var result = service(true).parse(OpenApiPolicyFixtures.document("3.1.0", "indirect", yaml, uri));
                assertThat(result.isSuccess()).isFalse();
                assertThat(result.getErrors()).contains(OpenApiLocalReferenceGuard.EXTERNAL_DISABLED);
                assertThat(result.getRules()).isEmpty();
                assertThat(requests.get()).isEqualTo(before);
            }
        }
    }

    @Test
    void rejectedFileReferencesDoNotReadTheReferencedFile() throws Exception {
        assertThat(fileReads("positive-control", () -> {
            try (var input = new FileInputStream(schemaFile.toFile())) {
                assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .contains(OpenApiPolicyFixtures.MARKER);
            }
        })).isPositive();
        assertThat(fileReads("protected-import", () -> {
            var result = service(true).parse(OpenApiPolicyFixtures.document("3.1.0", "external", false,
                    schemaFile.toUri().toString()));
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrors()).contains(OpenApiLocalReferenceGuard.EXTERNAL_DISABLED);
        })).isZero();
    }

    private static long fileReads(String name, CheckedAction action) throws Exception {
        Path recordingFile = ownedDirectory.resolve(name + ".jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace();
            recording.start();
            action.run();
            recording.stop();
            recording.dump(recordingFile);
        }
        long count = 0;
        try (var recording = new RecordingFile(recordingFile)) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                if (event.getEventType().getName().equals("jdk.FileRead")) {
                    String path = event.getString("path");
                    if (path != null && Path.of(path).toAbsolutePath().normalize().equals(schemaFile.normalize())) count++;
                }
            }
        }
        return count;
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    @Test
    void schemaIdsCannotCauseAnUnobservedRemoteFetch() throws Exception {
        for (String kind : List.of("schema-id", "id-indirect")) {
            for (boolean yaml : List.of(false, true)) {
                int before = requests.get();
                service(true).parse(OpenApiPolicyFixtures.document("3.1.0", kind, yaml, remote()));
                assertThat(requests.get()).isEqualTo(before);
            }
        }
    }

    @Test
    void defaultBindingRetainsExternalReferencesAndOptInBindingRejectsThem() throws Exception {
        String input = OpenApiPolicyFixtures.document("3.0.3", "external", false, remote());
        var context = new ApplicationContextRunner().withBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                () -> OpenApiPolicyFixtures.JSON).withUserConfiguration(OpenApiImportService.class);
        int before = requests.get();
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(OpenApiImportService.class).parse(input).isSuccess()).isTrue();
        });
        assertThat(requests.get()).isEqualTo(before + 1);
        context.withPropertyValues("echo.openapi.local-references-only=true").run(ctx -> {
            var result = ctx.getBean(OpenApiImportService.class).parse(input);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrors()).contains(OpenApiLocalReferenceGuard.EXTERNAL_DISABLED);
        });
        assertThat(requests.get()).isEqualTo(before + 1);
    }

    @Test
    void concurrentImportsHaveNoSharedReferenceOrResultState() throws Exception {
        var strict = service(true);
        int before = requests.get();
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> work = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String marker = "request-" + i;
                String valid = OpenApiPolicyFixtures.document("3.1.0", "recursive-ref", false, remote())
                        .replace(OpenApiPolicyFixtures.MARKER, marker);
                String rejected = OpenApiPolicyFixtures.document("3.1.0", "indirect", false, remote());
                work.add(() -> {
                    var result = strict.parse(valid);
                    return result.isSuccess() && result.getRules().get(0).getResponseBody().contains(marker)
                            && !strict.parse(rejected).isSuccess();
                });
            }
            for (var outcome : executor.invokeAll(work)) assertThat(outcome.get()).isTrue();
        } finally { executor.shutdownNow(); }
        assertThat(requests.get()).isEqualTo(before);
    }

    @Test
    void malformedDocumentsStillProduceFailures() {
        assertThat(service(true).parse("not an OpenAPI document").isSuccess()).isFalse();
    }

    private static OpenApiImportService service(boolean strict) {
        var result = new OpenApiImportService(OpenApiPolicyFixtures.JSON);
        ReflectionTestUtils.setField(result, "localReferencesOnly", strict);
        return result;
    }

    private static String remote() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/schema.json"; }
}
