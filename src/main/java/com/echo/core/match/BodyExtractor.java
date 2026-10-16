package com.echo.core.match;

/** 內建的 body 取值器，向 {@link BodyRequirements} 說明自己需要怎麼解析 body。 */
interface BodyExtractor extends ValueExtractor {

    /** 可串流的 XML 簡單路徑；不是 XPath，或 XPath 不是簡單路徑時為 null。 */
    XmlPath xmlPath();

    /** 是否需要 XML DOM（無法串流的 XPath）。JSON 與原文取值不需要。 */
    boolean needsXmlDom();
}
