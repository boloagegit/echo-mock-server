package com.echo.core.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compile {@link MatchNode} into {@link CompiledMatcher}, and check the operators according to {@link MatcherRegistry},
 * Sources and options. All errors are reported at compile time (that is, when the rules are stored) and will not be left until the request comes in to become "non-conforming".
 */
public final class MatcherCompiler {

    private final MatcherRegistry registry;
    private final MatcherLimits limits;

    public MatcherCompiler(MatcherRegistry registry, MatcherLimits limits) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public static MatcherCompiler withDefaults() {
        return new MatcherCompiler(MatcherRegistry.defaults(), MatcherLimits.DEFAULT);
    }

    /**
     * Parse and compile JSON condition.
     *
     * @throws InvalidMatchSpecException Illegal condition
     */
    public CompiledMatcher compile(JsonNode spec) {
        return compile(MatchSpecCodec.parse(spec));
    }

    /**
     * Compile condition tree.
     *
     * @throws InvalidMatchSpecException Illegal condition
     */
    public CompiledMatcher compile(MatchNode node) {
        return compile(node, "$");
    }

    private CompiledMatcher compile(MatchNode node, String path) {
        String cacheKey = MatchSpecCodec.toJson(node).toString();
        if (node instanceof MatchNode.All all) {
            return new CompiledNodes.All(compileChildren(all.children(), path + ".all"), cacheKey);
        }
        if (node instanceof MatchNode.Any any) {
            return new CompiledNodes.Any(compileChildren(any.children(), path + ".any"), cacheKey);
        }
        if (node instanceof MatchNode.Not not) {
            return new CompiledNodes.Not(compile(not.child(), path + ".not"), cacheKey);
        }
        if (node instanceof MatchNode.Legacy legacy) {
            return new LegacyConditionMatcher(legacy.source(), legacy.condition(), limits);
        }
        return compileLeaf((MatchNode.Leaf) node, path, cacheKey);
    }

    private List<CompiledMatcher> compileChildren(List<MatchNode> children, String path) {
        List<CompiledMatcher> compiled = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            compiled.add(compile(children.get(i), path + "[" + i + "]"));
        }
        return compiled;
    }

    private CompiledMatcher compileLeaf(MatchNode.Leaf leaf, String path, String cacheKey) {
        ValueSourceFactory source = registry.source(leaf.source()).orElseThrow(() ->
                new InvalidMatchSpecException(path + ".source",
                        "unknown source '" + leaf.source() + "' (known: " + registry.sources() + ")"));
        validateName(source, leaf, path);
        validateSelector(source, leaf, path);

        PredicateFactory predicate = registry.predicate(leaf.op()).orElseThrow(() ->
                new InvalidMatchSpecException(path + ".op",
                        "unknown op '" + leaf.op() + "' (known: " + registry.ops() + ")"));
        JsonNode value = leaf.value();
        if (predicate.requiresValue() && value == null) {
            throw new InvalidMatchSpecException(path + ".value", "is required for op '" + leaf.op() + "'");
        }
        if (!predicate.requiresValue() && value != null) {
            throw new InvalidMatchSpecException(path + ".value", "is not allowed for op '" + leaf.op() + "'");
        }
        if (!predicate.allowsOnMissing() && leaf.onMissing() != MatchNode.OnMissing.AUTO) {
            throw new InvalidMatchSpecException(path + ".onMissing",
                    "is not allowed for op '" + leaf.op() + "'");
        }
        Map<String, JsonNode> options = leaf.options();
        Set<String> supported = predicate.supportedOptions();
        for (String option : options.keySet()) {
            if (!supported.contains(option)) {
                throw new InvalidMatchSpecException(path + ".options." + option,
                        "is not supported by op '" + leaf.op() + "' (supported: " + supported + ")");
            }
        }

        ValueExtractor extractor;
        try {
            extractor = source.create(leaf.name(), leaf.selector(), limits);
        } catch (IllegalArgumentException e) {
            throw new InvalidMatchSpecException(path + (leaf.selector() != null ? ".selector" : ".source"),
                    e.getMessage(), e);
        }
        CompiledPredicate compiledPredicate;
        try {
            compiledPredicate = predicate.compile(value, options, limits);
        } catch (IllegalArgumentException e) {
            throw new InvalidMatchSpecException(path, e.getMessage(), e);
        }

        boolean resultWhenMissing = switch (leaf.onMissing()) {
            case PASS -> true;
            case FAIL -> false;
            case AUTO -> predicate.passesWhenMissing();
        };
        return new CompiledNodes.Leaf(extractor, compiledPredicate, leaf.quantifier(), resultWhenMissing,
                limits.maxFieldValueLength(), describe(leaf), leaf, cacheKey, source.readsBody());
    }

    private static void validateName(ValueSourceFactory source, MatchNode.Leaf leaf, String path) {
        if (source.requiresName() && leaf.name() == null) {
            throw new InvalidMatchSpecException(path + ".name", "is required for source '" + leaf.source() + "'");
        }
        if (!source.requiresName() && leaf.name() != null) {
            throw new InvalidMatchSpecException(path + ".name",
                    "is not allowed for source '" + leaf.source() + "'");
        }
    }

    private static void validateSelector(ValueSourceFactory source, MatchNode.Leaf leaf, String path) {
        Set<Selector.Kind> supported = source.supportedSelectors();
        if (supported.isEmpty()) {
            if (leaf.selector() != null) {
                throw new InvalidMatchSpecException(path + ".selector",
                        "is not allowed for source '" + leaf.source() + "'");
            }
            return;
        }
        if (leaf.selector() == null) {
            throw new InvalidMatchSpecException(path + ".selector",
                    "is required for source '" + leaf.source() + "'");
        }
        if (!supported.contains(leaf.selector().kind())) {
            throw new InvalidMatchSpecException(path + ".selector",
                    leaf.selector().kind().jsonName() + " is not supported for source '" + leaf.source() + "'");
        }
    }

    /** For example, {@code header[X-Tenant] equals "acme"}, {@code body(jsonPath: $.amount) gte 1000}. */
    private static String describe(MatchNode.Leaf leaf) {
        StringBuilder text = new StringBuilder(leaf.source());
        if (leaf.name() != null) {
            text.append('[').append(leaf.name()).append(']');
        }
        if (leaf.selector() != null) {
            text.append('(').append(leaf.selector().kind().jsonName());
            if (leaf.selector().expression() != null) {
                text.append(": ").append(leaf.selector().expression());
            }
            text.append(')');
        }
        text.append(' ').append(leaf.op());
        if (leaf.value() != null) {
            text.append(' ').append(leaf.value());
        }
        if (leaf.quantifier() != MatchNode.Quantifier.ANY) {
            text.append(" (quantifier: all)");
        }
        if (leaf.onMissing() != MatchNode.OnMissing.AUTO) {
            text.append(" (onMissing: ").append(leaf.onMissing().name().toLowerCase(Locale.ROOT))
                    .append(')');
        }
        return text.toString();
    }
}
