package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Document;

/**
 * Requesting read-only view of body. Implementations can defer parsing and reuse parsing results within the same request.
 * <p>
 * The returned {@link JsonNode} and {@link Document} are parsing results shared by the calling end and cannot be modified.
 */
public interface BodyView {

    /** Original body; null without body. */
    String raw();

    /** Body exceeds the upper size limit and cannot determine any body conditions. */
    boolean tooLarge();

    /** JSON after parsing; null if not JSON or too large. */
    JsonNode json();

    /** Parsed XML DOM (regardless of namespace); not XML or null if it is too large. */
    Document xml();

    /**
     * The value is obtained based on {@link XmlPath} (the rules are the same as stream scanning); if it is not XML, it is null.
     * The implementation can cache within the same request, allowing multiple conditions to share the results of the same path.
     */
    default java.util.List<String> xmlValues(XmlPath path) {
        Document doc = xml();
        return doc == null ? null : path.select(doc);
    }

    static BodyView of(String raw, MatcherLimits limits) {
        return new LazyBodyView(raw, limits);
    }

    static BodyView empty() {
        return new LazyBodyView(null, MatcherLimits.DEFAULT);
    }
}
