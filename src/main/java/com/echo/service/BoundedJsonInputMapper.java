package com.echo.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapts long plain-string inputs without replacing Jackson's first-root validation.
 * Eight lazily allocated buffers retain at most 2 MiB; busy slots never delay a caller.
 */
final class BoundedJsonInputMapper {
    private static final ObjectMapper DEFAULTS = new ObjectMapper();
    private static final int MAX_CHARS = 131072;
    private static final Slot[] SLOTS = slots();

    private BoundedJsonInputMapper() {}

    static JsonNode readTree(ObjectMapper mapper, String source) throws IOException {
        // Keep the common short-input gate small enough for straightforward JIT inlining.
        if (source.length() <= 32768 || source.length() > MAX_CHARS) {
            return mapper.readTree(source);
        }
        return readLargeTree(mapper, source);
    }

    private static JsonNode readLargeTree(ObjectMapper mapper, String source) throws IOException {
        if (!supported(mapper) || !hasDominantPlainString(source)) {
            return mapper.readTree(source);
        }
        for (Slot slot : SLOTS) {
            if (!slot.busy.compareAndSet(false, true)) continue;
            try {
                if (slot.buffer == null) slot.buffer = new char[MAX_CHARS];
                source.getChars(0, source.length(), slot.buffer, 0);
                try (JsonParser parser =
                        mapper.getFactory().createParser(slot.buffer, 0, source.length())) {
                    return mapper.readTree(parser);
                }
            } finally {
                if (slot.buffer != null) Arrays.fill(slot.buffer, 0, source.length(), '\0');
                slot.busy.set(false);
            }
        }
        // A busy pool never delays a request; the existing parser remains the fallback.
        return mapper.readTree(source);
    }

    @SuppressWarnings(
            "ReferenceEquality") // Null-ID modules can replace deserializers without a registered
    // ID.
    private static boolean supported(ObjectMapper mapper) {
        Class<?> type = mapper.getFactory().getClass();
        return mapper.getClass() == ObjectMapper.class
                && (type == MappingJsonFactory.class || type == JsonFactory.class)
                && mapper.getFactory().getInputDecorator() == null
                && mapper.getDeserializationContext().getFactory()
                        == DEFAULTS.getDeserializationContext().getFactory()
                && mapper.getRegisteredModuleIds().isEmpty()
                && mapper.mixInCount() == 0
                // Pooled input must not escape through exception source references.
                && !mapper.getFactory().isEnabled(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)
                && mapper.getFactory().streamReadConstraints() == StreamReadConstraints.defaults();
    }

    private static boolean hasDominantPlainString(String source) {
        int size = source.length(), i = whitespace(source, 0);
        if (i == size || source.charAt(i++) != '{') return false;
        // Scan for escapes once, rather than rescanning the remaining document per field.
        if (source.indexOf('\\') >= 0) return false;
        for (int field = 0; field < 8; field++) {
            i = whitespace(source, i);
            if (i == size || source.charAt(i++) != '"') return false;
            int end = source.indexOf('"', i);
            if (end < 0) return false;
            i = whitespace(source, end + 1);
            if (i == size || source.charAt(i++) != ':') return false;
            i = whitespace(source, i);
            if (i == size || source.charAt(i++) != '"') return false;
            end = source.indexOf('"', i);
            if (end < 0) return false;
            if (end - i >= size * 3 / 4) return true;
            i = whitespace(source, end + 1);
            if (i == size || source.charAt(i++) != ',') return false;
        }
        return false;
    }

    private static int whitespace(String source, int i) {
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
            i++;
        }
        return i;
    }

    private static Slot[] slots() {
        Slot[] result = new Slot[8];
        for (int i = 0; i < result.length; i++) result[i] = new Slot();
        return result;
    }

    private static final class Slot {
        private final AtomicBoolean busy = new AtomicBoolean();
        private char[] buffer;
    }
}
