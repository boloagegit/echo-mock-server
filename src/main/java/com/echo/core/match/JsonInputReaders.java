package com.echo.core.match;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Keeps unusually long, changing field names out of shared Jackson symbol caches. */
public final class JsonInputReaders {
    private static final ObjectMapper DEFAULTS = new ObjectMapper();
    private static final int CACHED_NAME_CHARS = 128;

    private JsonInputReaders() {}

    @SuppressWarnings(
            "ReferenceEquality") // Only the pristine default deserializer factory is eligible.
    public static ObjectMapper reader(ObjectMapper mapper, String source) {
        if (source.length() <= CACHED_NAME_CHARS + 4 || !hasLongPotentialName(source))
            return mapper;
        Class<?> factory = mapper.getFactory().getClass();
        if (mapper.getClass() != ObjectMapper.class
                || (factory != JsonFactory.class && factory != MappingJsonFactory.class)
                || mapper.getFactory().getInputDecorator() != null
                || mapper.getDeserializationContext().getFactory()
                        != DEFAULTS.getDeserializationContext().getFactory()
                || !mapper.getRegisteredModuleIds().isEmpty()
                || mapper.mixInCount() != 0) return mapper;
        ObjectMapper isolated = mapper.copy();
        isolated.getFactory().disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES);
        isolated.getFactory().disable(JsonFactory.Feature.INTERN_FIELD_NAMES);
        return isolated;
    }

    private static boolean hasLongPotentialName(String source) {
        int at = 0;
        while ((at = source.indexOf('"', at)) >= 0) {
            int start = at++, end;
            while (true) {
                end = source.indexOf('"', at);
                if (end < 0) return false;
                int slash = end;
                while (slash > start && source.charAt(slash - 1) == '\\') slash--;
                if ((end - slash) % 2 == 0) break;
                at = end + 1;
            }
            if (end - start - 1 > CACHED_NAME_CHARS) {
                int before = start - 1;
                while (before >= 0 && whitespace(source.charAt(before))) before--;
                // Arrays may conservatively select this path. A malformed colon must
                // not let a name enter the shared cache before parsing fails.
                if (before >= 0 && (source.charAt(before) == '{' || source.charAt(before) == ','))
                    return true;
            }
            at = end + 1;
        }
        return false;
    }

    private static boolean whitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
}
