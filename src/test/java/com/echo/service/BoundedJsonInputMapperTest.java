package com.echo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

class BoundedJsonInputMapperTest {
    private static final String BODY =
            "{\"token\":\"yes\",\"padding\":\"" + "x".repeat(65500) + "\"}";

    @Test
    void preservesResultsAtInputBoundariesAndForOtherBodyShapes() throws Exception {
        var mapper = new ObjectMapper();
        for (int length :
                List.of(32767, 32768, 32769, 65535, 65536, 65537, 131071, 131072, 131073)) {
            equivalent(mapper, "{\"padding\":\"" + "x".repeat(length - 14) + "\"}");
        }
        for (String input :
                List.of(
                        "",
                        "null",
                        "true",
                        "42",
                        "[]",
                        "{\"token\":\"yes\"}",
                        "{\"token\":\"yes\",\"unused\":[" + "{\"n\":1},".repeat(8000) + "{}]}",
                        "{\"token\":\"yes\",\"padding\":\"" + "\\u0061".repeat(11000) + "\"}",
                        "{\"token\":\"yes\",\"padding\":\"" + "中文😀".repeat(16000) + "\"}")) {
            equivalent(mapper, input);
        }
    }

    @Test
    void validatesTheEntireFirstRootAndPreservesDuplicateAndTrailingRootBehavior()
            throws Exception {
        var mapper = new ObjectMapper();
        for (String tail :
                List.of(
                        "",
                        ",\"token\":\"no\"",
                        ",\"v\":null",
                        ",\"v\":1e999999999",
                        ",\"v\":\"bad\\q\"",
                        ",\"v\":\"bad\n\"",
                        ",\"v\":",
                        ",\"v\":" + "9".repeat(1100),
                        ",\"v\":" + "[".repeat(1001) + "0" + "]".repeat(1001))) {
            for (String trailing : List.of("", "garbage", "{}", " null")) {
                equivalent(mapper, BODY.substring(0, BODY.length() - 1) + tail + "}" + trailing);
            }
        }
        Random random = new Random(20261021);
        char[] changes = {'"', '\\', '{', '}', ':', ',', '\n', '\u0000', '中'};
        for (int i = 0; i < 300; i++) {
            int at = random.nextInt(BODY.length());
            equivalent(
                    mapper,
                    BODY.substring(0, at)
                            + changes[random.nextInt(changes.length)]
                            + BODY.substring(at + 1));
        }
    }

    @Test
    void preservesConfiguredParserAndTreeFeatures() throws Exception {
        for (DeserializationFeature feature : DeserializationFeature.values()) {
            var mapper = new ObjectMapper();
            mapper.configure(feature, !mapper.isEnabled(feature));
            for (String input :
                    List.of(
                            BODY,
                            BODY + "garbage",
                            BODY + "{}",
                            BODY.substring(0, BODY.length() - 1) + ",\"token\":\"no\"}"))
                equivalent(mapper, input);
        }
        for (JsonParser.Feature feature : JsonParser.Feature.values()) {
            var mapper = new ObjectMapper();
            mapper.getFactory().configure(feature, !mapper.getFactory().isEnabled(feature));
            equivalent(mapper, BODY);
            equivalent(mapper, BODY + "garbage");
        }
    }

    @Test
    void customDeserializersKeepTheirOriginalInputContract() throws Exception {
        var module =
                new SimpleModule()
                        .addDeserializer(
                                JsonNode.class,
                                new StdDeserializer<JsonNode>(JsonNode.class) {
                                    @Override
                                    public JsonNode deserialize(
                                            JsonParser parser, DeserializationContext context)
                                            throws IOException {
                                        String sourceType =
                                                parser.getInputSource().getClass().getName();
                                        parser.skipChildren();
                                        return TextNode.valueOf(sourceType);
                                    }
                                });
        var mapper = new ObjectMapper().registerModule(module);
        assertThat(BoundedJsonInputMapper.readTree(mapper, BODY).asText())
                .isEqualTo(StringReader.class.getName());
    }

    @Test
    void customDeserializersWithoutModuleIdsKeepTheirOriginalInputContract() throws Exception {
        var module =
                new SimpleModule() {
                    @Override
                    public Object getTypeId() {
                        return null;
                    }
                };
        module.addDeserializer(
                JsonNode.class,
                new StdDeserializer<JsonNode>(JsonNode.class) {
                    @Override
                    public JsonNode deserialize(JsonParser parser, DeserializationContext context)
                            throws IOException {
                        Object input = parser.getInputSource();
                        parser.skipChildren();
                        return TextNode.valueOf(
                                input == null ? "no input source" : input.getClass().getName());
                    }
                });
        var mapper = new ObjectMapper().registerModule(module);
        assertThat(mapper.getRegisteredModuleIds()).isEmpty();
        assertThat(mapper.readTree(BODY).asText()).isEqualTo(StringReader.class.getName());
        assertThat(BoundedJsonInputMapper.readTree(mapper, BODY).asText())
                .isEqualTo(StringReader.class.getName());
    }

    @Test
    void exceptionSourceReferencesKeepTheirOriginalReaderWhenExplicitlyEnabled() {
        var mapper = new ObjectMapper().enable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);
        Throwable failure =
                catchThrowable(
                        () ->
                                BoundedJsonInputMapper.readTree(
                                        mapper,
                                        BODY.substring(0, BODY.length() - 1) + ",\"invalid\":}"));
        assertThat(failure).isInstanceOf(JsonProcessingException.class);
        var location = ((JsonProcessingException) failure).getLocation();
        assertThat(location.contentReference().getRawContent()).isInstanceOf(StringReader.class);
    }

    @Test
    void customReadConstraintsRetainTheOriginalValidation() throws Exception {
        for (String constraint :
                List.of("string", "name", "number", "document", "tokens", "depth")) {
            var limits = StreamReadConstraints.builder();
            switch (constraint) {
                case "string" -> limits.maxStringLength(10);
                case "name" -> limits.maxNameLength(2);
                case "number" -> limits.maxNumberLength(2);
                case "document" -> limits.maxDocumentLength(1000);
                case "tokens" -> limits.maxTokenCount(3);
                case "depth" -> limits.maxNestingDepth(1);
                default -> throw new AssertionError(constraint);
            }
            var mapper = new ObjectMapper();
            mapper.getFactory().setStreamReadConstraints(limits.build());
            equivalent(mapper, BODY);
            equivalent(mapper, BODY.substring(0, BODY.length() - 1) + ",\"nested\":[[1234]]}");
        }
    }

    @Test
    void concurrentBorrowersCannotShareResultsAndReturnedBuffersAreCleared() throws Exception {
        var mapper = new ObjectMapper();
        JsonNode retained = BoundedJsonInputMapper.readTree(mapper, BODY);
        var executor = Executors.newFixedThreadPool(24);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 240; i++) {
                int value = i;
                tasks.add(
                        () -> {
                            JsonNode tree =
                                    BoundedJsonInputMapper.readTree(
                                            mapper, BODY.replace("yes", Integer.toString(value)));
                            return tree.path("token").asText().equals(Integer.toString(value))
                                    && tree.path("padding").asText().equals("x".repeat(65500));
                        });
            }
            for (var result : executor.invokeAll(tasks)) assertThat(result.get()).isTrue();
        } finally {
            executor.shutdownNow();
        }
        assertThat(retained.path("padding").asText()).isEqualTo("x".repeat(65500));
        long bytes = 0;
        for (Object slot : slots()) {
            assertThat(busy(slot).get()).isFalse();
            Field field = slot.getClass().getDeclaredField("buffer");
            field.setAccessible(true);
            char[] buffer = (char[]) field.get(slot);
            if (buffer != null) {
                bytes += buffer.length * 2L;
                assertThat(buffer).containsOnly('\0');
            }
        }
        assertThat(bytes).isPositive().isLessThanOrEqualTo(2L * 1024 * 1024);
    }

    @Test
    void anExhaustedPoolFallsBackWithoutWaiting() throws Exception {
        Object[] slots = slots();
        for (Object slot : slots) busy(slot).set(true);
        try {
            assertThat(
                            BoundedJsonInputMapper.readTree(new ObjectMapper(), BODY)
                                    .path("token")
                                    .asText())
                    .isEqualTo("yes");
        } finally {
            for (Object slot : slots) busy(slot).set(false);
        }
    }

    private static void equivalent(ObjectMapper mapper, String input) throws Exception {
        JsonNode original = null, candidate = null;
        Exception before = null, after = null;
        try {
            original = mapper.readTree(input);
        } catch (Exception e) {
            before = e;
        }
        try {
            candidate = BoundedJsonInputMapper.readTree(mapper, input);
        } catch (Exception e) {
            after = e;
        }
        assertThat(candidate).isEqualTo(original);
        assertThat(after == null ? null : after.getClass())
                .isEqualTo(before == null ? null : before.getClass());
    }

    private static Object[] slots() throws Exception {
        Field field = BoundedJsonInputMapper.class.getDeclaredField("SLOTS");
        field.setAccessible(true);
        return (Object[]) field.get(null);
    }

    private static AtomicBoolean busy(Object slot) throws Exception {
        Field field = slot.getClass().getDeclaredField("busy");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(slot);
    }
}
