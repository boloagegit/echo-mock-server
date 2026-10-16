package com.echo.core.match;

import java.util.HashMap;
import java.util.Map;

/**
 * 協定無關的唯讀請求視圖，供 {@link ValueExtractor} 取值。
 * <p>
 * 不適用的欄位回傳 null（例如 JMS 沒有 method 與 query string）。
 */
public interface RequestView {

    String method();

    String path();

    /** 原始 query string，不含 {@code ?}。 */
    String queryString();

    /** Header 名稱不分大小寫；實作不保證 map 本身不分大小寫，請用 {@link #header(String)}。 */
    Map<String, String> headers();

    BodyView body();

    /** 以不分大小寫的方式取得 header 值，不存在時回傳 null。 */
    default String header(String name) {
        Map<String, String> headers = headers();
        if (headers == null || name == null) {
            return null;
        }
        String exact = headers.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    static RequestView of(String method, String path, String queryString,
                          Map<String, String> headers, BodyView body) {
        return new Simple(method, path, queryString, headers, body);
    }

    /** 簡單的不可變實作；略過 null 的 header 名稱或值。 */
    record Simple(String method, String path, String queryString,
                  Map<String, String> headers, BodyView body) implements RequestView {

        public Simple {
            Map<String, String> copy = new HashMap<>();
            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (name != null && value != null) {
                        copy.put(name, value);
                    }
                });
            }
            headers = Map.copyOf(copy);
            body = body == null ? BodyView.empty() : body;
        }
    }
}
