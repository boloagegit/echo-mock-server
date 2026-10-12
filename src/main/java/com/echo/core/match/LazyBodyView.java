package com.echo.core.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;

/**
 * 第一次存取時才解析 JSON 或 XML 的 {@link BodyView}。僅供單一請求、單一執行緒使用。
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

    private JsonNode parseJson() {
        String trimmed = trimmedOrNull();
        if (trimmed == null || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
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
            // 預設 handler 會把解析錯誤印到 stderr；非 XML body 屬於正常情況，靜默處理。
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

    /** 與舊版 ConditionMatcher 相同的防 XXE 設定。 */
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
