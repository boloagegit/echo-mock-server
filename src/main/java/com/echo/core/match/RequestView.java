package com.echo.core.match;

import java.util.HashMap;
import java.util.Map;

/**
 * Protocol-independent read-only request view for {@link ValueExtractor} value.
 * <p>
 * Inapplicable fields return null (for example, JMS does not have method and query string).
 */
public interface RequestView {

    String method();

    String path();

    /** Original query string, without {@code ?}. */
    String queryString();

    /** The Header name is not case-sensitive; implementation is not guaranteed. map itself is not case-sensitive, please use {@link #header(String)}. */
    Map<String, String> headers();

    BodyView body();

    /** Obtain the header value in a case-insensitive manner, and return null if it does not exist. */
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

    /** Simple immutable implementation; skip header name or value of null. */
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
