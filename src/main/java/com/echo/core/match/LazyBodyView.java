package com.echo.core.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * {@link BodyView} of JSON or XML is only parsed for the first time. For use by a single request and a single thread only.
 */
final class LazyBodyView implements BodyView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DefaultHandler SILENT_ERRORS = new DefaultHandler();

    private static final ThreadLocal<DocumentBuilder> DOC_BUILDER =
            ThreadLocal.withInitial(LazyBodyView::newDocumentBuilder);

    private final String raw;
    private final boolean tooLarge;
    private boolean jsonParsed;
    private JsonNode json;
    private boolean xmlParsed;
    private Document xml;
    private java.util.Map<XmlPath, java.util.List<String>> xmlValues;

    LazyBodyView(String raw, MatcherLimits limits) {
        this.raw = raw;
        this.tooLarge = raw != null && raw.length() > limits.maxBodyChars();
    }

    @Override
    public String raw() {
        return raw;
    }

    @Override
    public boolean tooLarge() {
        return tooLarge;
    }

    @Override
    public JsonNode json() {
        if (!jsonParsed) {
            jsonParsed = true;
            json = parseJson();
        }
        return json;
    }

    @Override
    public Document xml() {
        if (!xmlParsed) {
            xmlParsed = true;
            xml = parseXml();
        }
        return xml;
    }

    @Override
    public java.util.List<String> xmlValues(XmlPath path) {
        Document doc = xml();
        if (doc == null) {
            return null;
        }
        if (xmlValues == null) {
            xmlValues = new java.util.HashMap<>();
        }
        return xmlValues.computeIfAbsent(path, ignored -> path.select(doc));
    }

    private JsonNode parseJson() {
        String trimmed = trimmedOrNull();
        if (trimmed == null || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return JsonInputReaders.reader(MAPPER, trimmed).readTree(trimmed);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Document parseXml() {
        String trimmed = trimmedOrNull();
        if (trimmed == null || !trimmed.startsWith("<")) {
            return null;
        }
        try {
            DocumentBuilder builder = DOC_BUILDER.get();
            builder.reset();
            // By default, handler will print parsing errors to stderr; non-XML body is a normal situation and will be processed silently.
            builder.setErrorHandler(SILENT_ERRORS);
            return builder.parse(new InputSource(new StringReader(trimmed)));
        } catch (SAXException | IOException e) {
            return null;
        }
    }

    private String trimmedOrNull() {
        if (raw == null || tooLarge) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Same anti-XXE settings as the old version ConditionMatcher. */
    private static DocumentBuilder newDocumentBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Failed to create DocumentBuilder", e);
        }
    }
}
