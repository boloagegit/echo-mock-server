package com.echo.core.match;

/** The built-in body valuer explains to {@link BodyRequirements} how it needs to parse body. */
interface BodyExtractor extends ValueExtractor {

    /** Streamable XML simple path; not XPath, or null when XPath is not a simple path. */
    XmlPath xmlPath();

    /** Is XML DOM required (XPath cannot be streamed). JSON does not need to be the same as the original value. */
    boolean needsXmlDom();
}
