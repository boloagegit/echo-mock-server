package com.echo.jms;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;

/**
 * 從 JMS body 串流提取 endpoint 欄位，避免只為取得 ServiceName 就建立完整 XML DOM / JSON tree。
 */
@Component
@Slf4j
public class JmsEndpointExtractor {

    private static final int MAX_ENDPOINT_VALUE_CHARS = 4096;
    private final JsonFactory jsonFactory = new JsonFactory();

    private static final ThreadLocal<XMLInputFactory> XML_INPUT_FACTORY = ThreadLocal.withInitial(() -> {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory;
    });

    public String extract(String body, String field) {
        if (body == null || body.isBlank() || field == null || field.isBlank()) {
            return null;
        }

        int first = firstNonWhitespace(body);
        if (first < 0) {
            return null;
        }

        try {
            return switch (body.charAt(first)) {
                case '<' -> extractXml(body, field);
                case '{' -> extractJson(body, field);
                default -> null;
            };
        } catch (Exception e) {
            log.debug("Failed to extract JMS endpoint field '{}': {}", field, e.getMessage());
            return null;
        }
    }

    @PreDestroy
    void cleanup() {
        XML_INPUT_FACTORY.remove();
    }

    private String extractXml(String body, String field) throws Exception {
        XMLStreamReader reader = XML_INPUT_FACTORY.get().createXMLStreamReader(new StringReader(body));
        try {
            int depth = 0;
            int targetDepth = -1;
            StringBuilder value = null;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (targetDepth < 0 && field.equals(reader.getLocalName())) {
                        targetDepth = depth;
                        value = new StringBuilder(Math.min(64, MAX_ENDPOINT_VALUE_CHARS));
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) && targetDepth >= 0) {
                    if (value.length() + reader.getTextLength() > MAX_ENDPOINT_VALUE_CHARS) {
                        return null;
                    }
                    value.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (targetDepth == depth) {
                        return normalize(value.toString());
                    }
                    depth--;
                }
            }
            return null;
        } finally {
            reader.close();
        }
    }

    private String extractJson(String body, String field) throws Exception {
        try (JsonParser parser = jsonFactory.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    parser.skipChildren();
                    continue;
                }
                String name = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (!field.equals(name)) {
                    parser.skipChildren();
                    continue;
                }
                if (valueToken == null || !valueToken.isScalarValue()) {
                    return null;
                }
                String value = parser.getValueAsString();
                return value != null && value.length() <= MAX_ENDPOINT_VALUE_CHARS
                        ? normalize(value) : null;
            }
            return null;
        }
    }

    private static int firstNonWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
