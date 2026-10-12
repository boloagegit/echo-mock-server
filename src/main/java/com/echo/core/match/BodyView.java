package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Document;

/**
 * 請求 body 的唯讀視圖。實作可延遲解析，並在同一請求內重用解析結果。
 * <p>
 * 回傳的 {@link JsonNode} 與 {@link Document} 屬於呼叫端共用的解析結果，不可修改。
 */
public interface BodyView {

    /** 原始 body；沒有 body 時為 null。 */
    String raw();

    /** Body 超過大小上限，無法判斷任何 body 條件。 */
    boolean tooLarge();

    /** 解析後的 JSON；不是 JSON 或過大時為 null。 */
    JsonNode json();

    /** 解析後的 XML DOM（不分 namespace）；不是 XML 或過大時為 null。 */
    Document xml();

    static BodyView of(String raw, MatcherLimits limits) {
        return new LazyBodyView(raw, limits);
    }

    static BodyView empty() {
        return new LazyBodyView(null, MatcherLimits.DEFAULT);
    }
}
