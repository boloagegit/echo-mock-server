package com.echo.core.match;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registry of operators and value sources. Once created, it is immutable and can be shared among multiple threads.
 */
public final class MatcherRegistry {

    private final Map<String, PredicateFactory> predicates;
    private final Map<String, ValueSourceFactory> sources;

    private MatcherRegistry(Map<String, PredicateFactory> predicates,
                            Map<String, ValueSourceFactory> sources) {
        this.predicates = Map.copyOf(predicates);
        this.sources = Map.copyOf(sources);
    }

    /** Registry containing only built-in operators and sources. */
    public static MatcherRegistry defaults() {
        return builder().withDefaults().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<PredicateFactory> predicate(String op) {
        return Optional.ofNullable(predicates.get(op));
    }

    public Optional<ValueSourceFactory> source(String source) {
        return Optional.ofNullable(sources.get(source));
    }

    public Set<String> ops() {
        return predicates.keySet();
    }

    public Set<String> sources() {
        return sources.keySet();
    }

    public static final class Builder {

        private final Map<String, PredicateFactory> predicates = new LinkedHashMap<>();
        private final Map<String, ValueSourceFactory> sources = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder withDefaults() {
            BuiltinPredicates.all().forEach(this::register);
            BuiltinSources.all().forEach(this::register);
            return this;
        }

        /**
         * Registering operator.
         *
         * @throws IllegalStateException Duplicate operator name
         */
        public Builder register(PredicateFactory factory) {
            if (predicates.putIfAbsent(factory.op(), factory) != null) {
                throw new IllegalStateException("Duplicate match op: " + factory.op());
            }
            return this;
        }

        /**
         * Register value source.
         *
         * @throws IllegalStateException Duplicate source name
         */
        public Builder register(ValueSourceFactory factory) {
            if (sources.putIfAbsent(factory.source(), factory) != null) {
                throw new IllegalStateException("Duplicate match source: " + factory.source());
            }
            return this;
        }

        public MatcherRegistry build() {
            return new MatcherRegistry(predicates, sources);
        }
    }
}
