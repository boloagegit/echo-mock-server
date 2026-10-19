package com.echo.core.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Conversion between the three condition strings of the old version (bodyCondition, queryCondition, headerCondition) and {@link MatchNode}.
 * <p>
 * Conversion occurs in memory only; strings in the database remain unchanged.
 */
public final class LegacyConditions {

    /** Prefix for legacy fragments in {@link MatchExplanation#node()}, for example {@code legacy.body}. */
    static final String LEGACY_NODE_PREFIX = "legacy.";

    private LegacyConditions() {
    }

    /**
     * There are three condition strings in the old version. When there is no condition in any field, it is null.
     *
     * @param bodyCondition   body condition concatenated with {@code ;}
     * @param queryCondition  query condition concatenated with {@code ;}
     * @param headerCondition header condition concatenated with {@code ;}
     */
    public record Strings(String bodyCondition, String queryCondition, String headerCondition) {
    }

    /**
     * Fields, operators and expected values of a single legacy condition (all with leading and trailing whitespace removed).
     *
     * @param operator {@code =}, {@code !=}, {@code *=} or {@code ~=}
     */
    public record Parts(String field, String operator, String value) {
    }

    /**
     * Use the same rules to disassemble a single condition: look at the first </strong> {@code =} in <strong>, and the characters before it are {@code ~}, {@code *},
     * {@code ~=}, {@code *=}, {@code !=} respectively when {@code !}, otherwise {@code =}. For example, {@code a=x!=y} is the field
     * {@code a}, operator {@code =}, value {@code x!=y}. Both the front-end condition column and v1 file check follow this rule (see {@code match-tree.js}).
     *
     * @return There is no {@code =}, or {@code =} returns empty as the first character (the entire segment is treated as a field during comparison)
     */
    public static Optional<Parts> parts(String condition) {
        int eqIdx = condition.indexOf('=');
        if (eqIdx <= 0) {
            return Optional.empty();
        }
        String value = condition.substring(eqIdx + 1).trim();
        char previous = condition.charAt(eqIdx - 1);
        if (previous == '~' || previous == '*' || previous == '!') {
            return Optional.of(new Parts(condition.substring(0, eqIdx - 1).trim(), previous + "=", value));
        }
        return Optional.of(new Parts(condition.substring(0, eqIdx).trim(), "=", value));
    }

    /**
     * Convert the old version condition string to {@code all[legacy...]}, and the fragment order is the same as the old version evaluation order (body → query → header).
     * When there are no conditions in the three fields, an empty {@code all} is returned, which is an unconditional (fallback) rule.
     */
    public static MatchNode.All toMatchNode(String bodyCondition, String queryCondition, String headerCondition) {
        List<MatchNode> fragments = new ArrayList<>();
        addFragments(fragments, MatchNode.LegacySource.BODY, bodyCondition);
        addFragments(fragments, MatchNode.LegacySource.QUERY, queryCondition);
        addFragments(fragments, MatchNode.LegacySource.HEADER, headerCondition);
        return new MatchNode.All(fragments);
    }

    /**
     * If the condition tree can be losslessly represented as an old version string (empty {@code all}, or {@code all} containing only old version fragments), return the corresponding string.
     * A conditional tree using {@code any}, {@code not} or a new operator returns empty. The caller cannot write it back to the old field.
     */
    public static Optional<Strings> toStrings(MatchNode node) {
        if (!(node instanceof MatchNode.All all)) {
            return Optional.empty();
        }
        List<String> body = new ArrayList<>();
        List<String> query = new ArrayList<>();
        List<String> header = new ArrayList<>();
        for (MatchNode child : all.children()) {
            if (!(child instanceof MatchNode.Legacy legacy)) {
                return Optional.empty();
            }
            switch (legacy.source()) {
                case BODY -> body.add(legacy.condition());
                case QUERY -> query.add(legacy.condition());
                case HEADER -> header.add(legacy.condition());
            }
        }
        return Optional.of(new Strings(join(body), join(query), join(header)));
    }

    /**
     * Produces exactly the same conditional specification as the old version of the match chain, e.g.
     * {@code bodyCondition FAIL (expected user.type=vip, actual user.type=normal)}。
     *
     * @return Returning empty when describing a non-old fragment
     */
    public static Optional<String> legacyDetail(MatchExplanation explanation) {
        return sourceOf(explanation).map(source -> detail(source.fieldName(), explanation));
    }

    /** Which old field does the old version fragment indicate from? If it is not an old version fragment, return empty. */
    public static Optional<MatchNode.LegacySource> sourceOf(MatchExplanation explanation) {
        if (!explanation.node().startsWith(LEGACY_NODE_PREFIX)) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                MatchNode.LegacySource.fromJsonName(explanation.node().substring(LEGACY_NODE_PREFIX.length())));
    }

    private static String detail(String type, MatchExplanation explanation) {
        return explanation.passed()
                ? type + " PASS (" + explanation.expected() + ")"
                : type + " FAIL (expected " + explanation.expected() + ", actual " + explanation.actual() + ")";
    }

    private static void addFragments(List<MatchNode> fragments, MatchNode.LegacySource source, String condition) {
        if (condition == null || condition.isBlank()) {
            return;
        }
        for (String fragment : condition.split(";")) {
            String trimmed = fragment.trim();
            if (!trimmed.isEmpty()) {
                fragments.add(new MatchNode.Legacy(source, trimmed));
            }
        }
    }

    private static String join(List<String> fragments) {
        return fragments.isEmpty() ? null : String.join(";", fragments);
    }
}
