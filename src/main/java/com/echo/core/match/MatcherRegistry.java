package com.echo.core.match;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 運算子與取值來源的註冊表。建立後不可變，可在多執行緒間共用。
 */
public final class MatcherRegistry {

    private final Map<String, PredicateFactory> predicates;
    private final Map<String, ValueSourceFactory> sources;

    private MatcherRegistry(Map<String, PredicateFactory> predicates,
                            Map<String, ValueSourceFactory> sources) {
        this.predicates = Map.copyOf(predicates);
        this.sources = Map.copyOf(sources);
    }

    /** 只含內建運算子與來源的註冊表。 */
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
         * 註冊運算子。
         *
         * @throws IllegalStateException 運算子名稱重複
         */
        public Builder register(PredicateFactory factory) {
            if (predicates.putIfAbsent(factory.op(), factory) != null) {
                throw new IllegalStateException("Duplicate match op: " + factory.op());
            }
            return this;
        }

        /**
         * 註冊取值來源。
         *
         * @throws IllegalStateException 來源名稱重複
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
