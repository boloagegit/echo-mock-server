package com.echo.core.match;

import com.echo.core.util.InterruptibleCharSequence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Built-in operators.
 *
 * <table>
 *   <caption> operator </caption>
 *   <tr><th>op</th><th>Description</th><th>Option</th></tr>
 *   <tr><td>equals / notEquals</td><td> text equivalent (JSON 1.0 is not equal to 1, please use the numerical value gte/lte or equalToJson) </td>
 *       <td>caseInsensitive</td></tr>
 *   <tr><td>contains</td><td> contains the substring </td><td>caseInsensitive</td></tr>
 *   <tr><td>matches</td><td> regular full string comparison </td><td></td></tr>
 *   <tr><td>exists / absent</td><td> field exists/does not exist</td><td></td></tr>
 *   <tr><td>gt / gte / lt / lte</td><td>Numerical comparison</td><td></td></tr>
 *   <tr><td>in</td><td> belongs to any value in the list </td><td>caseInsensitive</td></tr>
 *   <tr><td>equalToJson</td><td>JSON structural equivalent </td><td>ignoreArrayOrder, ignoreExtraElements</td></tr>
 * </table>
 */
public final class BuiltinPredicates {

    static final String CASE_INSENSITIVE = "caseInsensitive";
    static final String IGNORE_ARRAY_ORDER = "ignoreArrayOrder";
    static final String IGNORE_EXTRA_ELEMENTS = "ignoreExtraElements";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BuiltinPredicates() {
    }

    public static List<PredicateFactory> all() {
        return List.of(
                valued("equals", Set.of(CASE_INSENSITIVE), false, BuiltinPredicates::equalsPredicate),
                valued("notEquals", Set.of(CASE_INSENSITIVE), true,
                        (expected, options, limits) -> negate(equalsPredicate(expected, options, limits))),
                valued("contains", Set.of(CASE_INSENSITIVE), false, BuiltinPredicates::contains),
                valued("matches", Set.of(), false, BuiltinPredicates::matches),
                presence("exists", true),
                presence("absent", false),
                valued("gt", Set.of(), false, numeric(cmp -> cmp > 0)),
                valued("gte", Set.of(), false, numeric(cmp -> cmp >= 0)),
                valued("lt", Set.of(), false, numeric(cmp -> cmp < 0)),
                valued("lte", Set.of(), false, numeric(cmp -> cmp <= 0)),
                valued("in", Set.of(CASE_INSENSITIVE), false, BuiltinPredicates::in),
                valued("equalToJson", Set.of(IGNORE_ARRAY_ORDER, IGNORE_EXTRA_ELEMENTS), false,
                        BuiltinPredicates::equalToJson));
    }

    // ==================== Operator implementation ====================

    private static CompiledPredicate equalsPredicate(JsonNode expected, Map<String, JsonNode> options,
                                                     MatcherLimits limits) {
        String expectedText = scalarText(expected, "equals");
        if (flag(options, CASE_INSENSITIVE)) {
            return actual -> expectedText == null
                    ? actual.text() == null
                    : expectedText.equalsIgnoreCase(actual.text());
        }
        return actual -> expectedText == null ? actual.text() == null : expectedText.equals(actual.text());
    }

    private static CompiledPredicate contains(JsonNode expected, Map<String, JsonNode> options,
                                              MatcherLimits limits) {
        String expectedText = requireText(scalarText(expected, "contains"), "contains");
        if (flag(options, CASE_INSENSITIVE)) {
            String lower = expectedText.toLowerCase(Locale.ROOT);
            return actual -> actual.text() != null && actual.text().toLowerCase(Locale.ROOT).contains(lower);
        }
        return actual -> actual.text() != null && actual.text().contains(expectedText);
    }

    private static CompiledPredicate matches(JsonNode expected, Map<String, JsonNode> options,
                                             MatcherLimits limits) {
        if (expected == null || !expected.isTextual()) {
            throw new IllegalArgumentException("matches requires a string pattern");
        }
        String regex = expected.asText();
        if (regex.length() > limits.maxRegexPatternLength()) {
            throw new IllegalArgumentException("Regex pattern exceeds "
                    + limits.maxRegexPatternLength() + " characters");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex: " + e.getDescription(), e);
        }
        return actual -> {
            String text = actual.text();
            if (text == null || text.length() > limits.maxRegexInputLength()) {
                return false;
            }
            try {
                return pattern.matcher(new InterruptibleCharSequence(text, limits.regexTimeoutMs())).matches();
            } catch (InterruptibleCharSequence.RegexTimeoutException e) {
                return false;
            } catch (StackOverflowError exhausted) {
                throw new RegexStackCapacityException(exhausted);
            }
        };
    }

    private static PredicateCompiler numeric(IntPredicate comparison) {
        return (expected, options, limits) -> {
            BigDecimal threshold = expected == null ? null : toDecimal(expected);
            if (threshold == null) {
                throw new IllegalArgumentException("Numeric comparison requires a number");
            }
            return actual -> {
                BigDecimal value = actual.json() != null && actual.json().isNumber()
                        ? actual.json().decimalValue()
                        : parseDecimal(actual.text());
                return value != null && comparison.test(value.compareTo(threshold));
            };
        };
    }

    private static CompiledPredicate in(JsonNode expected, Map<String, JsonNode> options,
                                        MatcherLimits limits) {
        if (expected == null || !expected.isArray()) {
            throw new IllegalArgumentException("in requires an array value");
        }
        List<CompiledPredicate> candidates = new ArrayList<>(expected.size());
        expected.forEach(item -> candidates.add(equalsPredicate(item, options, limits)));
        return actual -> {
            for (CompiledPredicate candidate : candidates) {
                if (candidate.test(actual)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static CompiledPredicate equalToJson(JsonNode expected, Map<String, JsonNode> options,
                                                 MatcherLimits limits) {
        if (expected == null) {
            throw new IllegalArgumentException("equalToJson requires a value");
        }
        JsonNode expectedJson = expected.deepCopy();
        boolean ignoreArrayOrder = flag(options, IGNORE_ARRAY_ORDER);
        boolean ignoreExtraElements = flag(options, IGNORE_EXTRA_ELEMENTS);
        return actual -> {
            JsonNode actualJson = actual.json() != null ? actual.json() : parseJson(actual.text());
            return actualJson != null
                    && JsonComparator.matches(expectedJson, actualJson, ignoreArrayOrder, ignoreExtraElements);
        };
    }

    // ==================== Tools ====================

    private static CompiledPredicate negate(CompiledPredicate predicate) {
        return actual -> !predicate.test(actual);
    }

    /** Literal form of scalar expected value; JSON null returns null. Please use equalToJson for arrays and objects instead. */
    private static String scalarText(JsonNode expected, String op) {
        if (expected == null || expected.isContainerNode()) {
            throw new IllegalArgumentException(op + " requires a string, number, boolean or null value");
        }
        if (expected.isNull()) {
            return null;
        }
        return expected.isTextual() ? expected.asText() : expected.toString();
    }

    private static String requireText(String text, String op) {
        if (text == null) {
            throw new IllegalArgumentException(op + " does not accept null");
        }
        return text;
    }

    private static boolean flag(Map<String, JsonNode> options, String name) {
        JsonNode value = options.get(name);
        if (value == null) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("Option " + name + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static BigDecimal toDecimal(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return node.isTextual() ? parseDecimal(node.asText()) : null;
    }

    private static BigDecimal parseDecimal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode parseJson(String text) {
        if (text == null) {
            return null;
        }
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static PredicateFactory valued(String op, Set<String> options, boolean passesWhenMissing,
                                           PredicateCompiler compiler) {
        return new SimplePredicate(op, options, true, passesWhenMissing, true, compiler);
    }

    /** exists/absent: Only check whether there is a value, value and onMissing are not accepted. */
    private static PredicateFactory presence(String op, boolean whenFound) {
        return new SimplePredicate(op, Set.of(), false, !whenFound, false,
                (expected, options, limits) -> actual -> whenFound);
    }

    @FunctionalInterface
    private interface PredicateCompiler {
        CompiledPredicate compile(JsonNode expected, Map<String, JsonNode> options, MatcherLimits limits);
    }

    private record SimplePredicate(String op, Set<String> supportedOptions, boolean requiresValue,
                                   boolean passesWhenMissing, boolean allowsOnMissing,
                                   PredicateCompiler compiler) implements PredicateFactory {

        SimplePredicate {
            supportedOptions = Set.copyOf(supportedOptions);
        }

        @Override
        public CompiledPredicate compile(JsonNode expected, Map<String, JsonNode> options, MatcherLimits limits) {
            return compiler.compile(expected, options, limits);
        }
    }
}
