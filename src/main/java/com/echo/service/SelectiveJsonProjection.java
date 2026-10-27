package com.echo.service;

import com.echo.core.match.BodyRequirements.JsonProjection;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Set;

/** Projects planned object paths while validating the complete first JSON root. */
final class SelectiveJsonProjection {
    private static final ObjectMapper DEFAULTS = new ObjectMapper();

    private SelectiveJsonProjection() {}

    static JsonNode project(ObjectMapper mapper, String source, Set<String> fields)
            throws IOException {
        return fields.isEmpty() || fields.size() > 16
                ? null
                : project(mapper, source, JsonProjection.fields(fields));
    }

    @SuppressWarnings(
            "ReferenceEquality") // Null-ID modules can still replace the default deserializer
    // factory.
    static JsonNode project(ObjectMapper mapper, String source, JsonProjection plan)
            throws IOException {
        Class<?> factory = mapper.getFactory().getClass();
        if (mapper.getClass() != ObjectMapper.class
                || (factory != JsonFactory.class && factory != MappingJsonFactory.class)
                || mapper.getFactory().getInputDecorator() != null
                || mapper.getFactory().getParserFeatures()
                        != DEFAULTS.getFactory().getParserFeatures()
                || mapper.getDeserializationConfig().getDeserializationFeatures()
                        != DEFAULTS.getDeserializationConfig().getDeserializationFeatures()
                || mapper.getDeserializationContext().getFactory()
                        != DEFAULTS.getDeserializationContext().getFactory()
                || mapper.getDeserializationConfig()
                                .getDefaultTyper(DEFAULTS.constructType(JsonNode.class))
                        != null
                || mapper.getDeserializationConfig()
                        .isEnabled(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                || !mapper.getDeserializationConfig()
                        .isEnabled(JsonNodeFeature.READ_NULL_PROPERTIES)
                || mapper.getNodeFactory().getClass() != JsonNodeFactory.class
                || !mapper.getRegisteredModuleIds().isEmpty()
                || mapper.mixInCount() != 0
                || plan == null
                || plan.children().isEmpty()
                || plan.children().size() > 16
                || source.length()
                        > mapper.getFactory().streamReadConstraints().getMaxStringLength())
            return null;
        try (JsonParser parser = mapper.getFactory().createParser(source)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return null;
            // Match readTree(String): validate the first root, without imposing a new EOF contract.
            return readProjected(mapper, parser, plan);
        }
    }

    private static JsonNode readProjected(
            ObjectMapper mapper, JsonParser parser, JsonProjection plan) throws IOException {
        if (plan.wholeValue() || parser.currentToken() != JsonToken.START_OBJECT) {
            JsonNode value = mapper.readTree(parser);
            if (value == null) throw new IOException("Incomplete JSON field");
            return value;
        }
        ObjectNode result = mapper.createObjectNode();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token != JsonToken.FIELD_NAME) throw new IOException("Incomplete JSON object");
            String field = parser.currentName();
            token = parser.nextToken();
            JsonProjection child = plan.children().get(field);
            if (child == null) validateValue(parser, token);
            else result.set(field, readProjected(mapper, parser, child));
        }
        return result;
    }

    private static void validateValue(JsonParser parser, JsonToken first) throws IOException {
        if (first == null) throw new IOException("Incomplete JSON value");
        int depth = first.isStructStart() ? 1 : 0;
        validateScalar(parser, first);
        while (depth > 0) {
            JsonToken token = parser.nextToken();
            if (token == null) throw new IOException("Incomplete JSON container");
            if (token.isStructStart()) depth++;
            if (token.isStructEnd()) depth--;
            validateScalar(parser, token);
        }
    }

    private static void validateScalar(JsonParser parser, JsonToken token) throws IOException {
        // nextToken validates grammar. Raw length conservatively bounds every counted
        // numeric component; longer ignored values keep the original conversion.
        // The outer source-length guard proves discarded decoded string lengths.
        if (token.isNumeric()
                && parser.getTextLength() > parser.streamReadConstraints().getMaxNumberLength())
            parser.getNumberValue();
    }
}
