package com.echo.core.match;

import java.util.Set;

/**
 * The expansion point of the value source. For built-in sources, see {@link BuiltinSources}; the protocol can register its own sources.
 * For example {@code jms.property}.
 */
public interface ValueSourceFactory {

    /** The value of the {@code source} field in condition JSON. */
    String source();

    /** Is {@code name} required (e.g. header, query). */
    boolean requiresName();

    /** selector is supported; an empty set means selector is not accepted, and selector is required if it is not empty. */
    Set<Selector.Kind> supportedSelectors();

    /**
     * Create a valuer. The parameters have passed the check of {@link #requiresName()} and {@link #supportedSelectors()}.
     *
     * @throws IllegalArgumentException The parameter is illegal (for example, JSONPath syntax error)
     */
    ValueExtractor create(String name, Selector selector, MatcherLimits limits);

    /**
     * Whether the value will read request body. The default is true, so that the source of self-registered and able to read body can be fully parsed body;
     * Read-only sources of header, query, etc. should return false so that rules without body conditions do not have to parse body.
     */
    default boolean readsBody() {
        return true;
    }
}
