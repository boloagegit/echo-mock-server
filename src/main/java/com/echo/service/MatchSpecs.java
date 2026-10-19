package com.echo.service;

import com.echo.core.match.InvalidMatchSpecException;
import com.echo.core.match.LegacyConditions;
import com.echo.core.match.MatchNode;
import com.echo.core.match.MatchSpecCodec;
import com.echo.core.match.MatcherCompiler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Check and normalize structured conditions ({@code matchSpec}) when storing rules.
 * <p>
 * All errors are reported when saving (including the JSON path), and will not be left until the request comes in to become "unconformed". Conditions that can be expressed using legacy condition strings
 * Three old fields will be transferred back. {@code matchSpec} only saves the truly required advanced conditions to avoid inconsistency between the old and new fields.
 */
public final class MatchSpecs {

    /** Maximum length (in characters) of condition JSON. */
    public static final int MAX_SPEC_CHARS = 64 * 1024;
    /** The combined node (all/any/not) has the maximum number of nested layers. */
    public static final int MAX_DEPTH = 16;
    /** The upper limit of the total number of conditional tree nodes. */
    public static final int MAX_NODES = 256;
    /** {@code in} The maximum number of items in the list. */
    public static final int MAX_IN_VALUES = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MatcherCompiler COMPILER = MatcherCompiler.withDefaults();

    private MatchSpecs() {
    }

    /**
     * Condition after normalization: {@code matchSpec} and the three old fields will only have a value on one side (when none of them are, it will be an unconditional rule).
     *
     * @param matchSpec       Normalized condition JSON; null when expressed using old fields
     * @param bodyCondition   Old version body condition
     * @param queryCondition  Old version query condition
     * @param headerCondition Old version header condition
     */
    public record Normalized(String matchSpec, String bodyCondition, String queryCondition,
                             String headerCondition) {

        public boolean advanced() {
            return matchSpec != null;
        }
    }

    /**
     * Check and normalize conditions.
     *
     * @param spec           User provided conditions JSON
     * @param allowedSources Available value sources for this protocol (see {@code ProtocolDescriptor#matchSources()})
     * @throws InvalidMatchSpecException The condition is illegal, the message contains the JSON path
     */
    public static Normalized normalize(JsonNode spec, Set<String> allowedSources) {
        if (spec == null || spec.isNull() || spec.isMissingNode()) {
            throw new InvalidMatchSpecException("$", "expected an object");
        }
        if (spec.toString().length() > MAX_SPEC_CHARS) {
            throw new InvalidMatchSpecException("$", "is longer than " + MAX_SPEC_CHARS + " characters");
        }
        MatchNode node = MatchSpecCodec.parse(spec);
        new Walker(allowedSources).walk(node, "$", 0);
        // Complete check of operators, sources, options and expected values
        COMPILER.compile(node);

        Optional<LegacyConditions.Strings> legacy = LegacyConditions.toStrings(node);
        if (legacy.isPresent()) {
            LegacyConditions.Strings strings = legacy.get();
            return new Normalized(null, strings.bodyCondition(), strings.queryCondition(),
                    strings.headerCondition());
        }
        return new Normalized(MatchSpecCodec.toJson(node).toString(), null, null, null);
    }

    /** Read the stored condition JSON; return empty when the format is incorrect (only for display, and protected during comparison). */
    public static Optional<JsonNode> read(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readTree(stored));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** Condition numbers (leaf nodes and legacy fragments) in the condition tree for use in automatically generated descriptions. */
    public static int conditionCount(String stored) {
        return read(stored).map(MatchSpecs::conditionCount).orElse(0);
    }

    public static int conditionCount(JsonNode spec) {
        if (spec == null || spec.isNull()) {
            return 0;
        }
        try {
            return count(MatchSpecCodec.parse(spec));
        } catch (InvalidMatchSpecException e) {
            return 0;
        }
    }

    /** The condition JSON of DTO is converted into a string for storage; when there is no condition, it is null. */
    public static String toStored(JsonNode spec) {
        return spec == null || spec.isNull() || spec.isMissingNode() ? null : spec.toString();
    }

    private static int count(MatchNode node) {
        if (node instanceof MatchNode.All all) {
            return all.children().stream().mapToInt(MatchSpecs::count).sum();
        }
        if (node instanceof MatchNode.Any any) {
            return any.children().stream().mapToInt(MatchSpecs::count).sum();
        }
        if (node instanceof MatchNode.Not not) {
            return count(not.child());
        }
        return 1;
    }

    /** Check nest level, node count, source and {@code in} manifest length. */
    private static final class Walker {
        private final Set<String> allowedSources;
        private int nodes;

        private Walker(Set<String> allowedSources) {
            this.allowedSources = allowedSources;
        }

        void walk(MatchNode node, String path, int depth) {
            if (++nodes > MAX_NODES) {
                throw new InvalidMatchSpecException(path, "has more than " + MAX_NODES + " nodes in total");
            }
            if (depth > MAX_DEPTH) {
                throw new InvalidMatchSpecException(path, "is nested deeper than " + MAX_DEPTH + " levels");
            }
            if (node instanceof MatchNode.All all) {
                walkChildren(all.children(), path + ".all", depth);
            } else if (node instanceof MatchNode.Any any) {
                walkChildren(any.children(), path + ".any", depth);
            } else if (node instanceof MatchNode.Not not) {
                walk(not.child(), path + ".not", depth + 1);
            } else if (node instanceof MatchNode.Legacy legacy) {
                requireSource(legacy.source().jsonName(), path + ".legacy.source");
            } else {
                MatchNode.Leaf leaf = (MatchNode.Leaf) node;
                requireSource(leaf.source(), path + ".source");
                JsonNode value = leaf.value();
                if ("in".equals(leaf.op()) && value != null && value.isArray() && value.size() > MAX_IN_VALUES) {
                    throw new InvalidMatchSpecException(path + ".value",
                            "has more than " + MAX_IN_VALUES + " values");
                }
            }
        }

        private void walkChildren(java.util.List<MatchNode> children, String path, int depth) {
            for (int i = 0; i < children.size(); i++) {
                walk(children.get(i), path + "[" + i + "]", depth + 1);
            }
        }

        private void requireSource(String source, String path) {
            if (!allowedSources.contains(source)) {
                throw new InvalidMatchSpecException(path, "source '" + source
                        + "' is not available for this protocol (available: " + new TreeSet<>(allowedSources) + ")");
            }
        }
    }
}
