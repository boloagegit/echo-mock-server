package com.echo.core.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@link CompiledMatcher} 的節點實作。 */
final class CompiledNodes {

    private static final int MAX_ACTUAL_VALUES_SHOWN = 5;
    private static final int MAX_ACTUAL_TEXT_LENGTH = 200;

    private CompiledNodes() {
    }

    static final class All implements CompiledMatcher {

        private final List<CompiledMatcher> children;
        private final String cacheKey;

        All(List<CompiledMatcher> children, String cacheKey) {
            this.children = List.copyOf(children);
            this.cacheKey = cacheKey;
        }

        @Override
        public boolean matches(EvalContext context) {
            for (CompiledMatcher child : children) {
                if (!child.matches(context)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public MatchExplanation explain(EvalContext context) {
            List<MatchExplanation> explained = explainAll(children, context);
            return new MatchExplanation("all", explained.stream().allMatch(MatchExplanation::passed),
                    null, null, explained);
        }

        @Override
        public String cacheKey() {
            return cacheKey;
        }

        @Override
        public boolean isUnconditional() {
            return children.isEmpty();
        }
    }

    static final class Any implements CompiledMatcher {

        private final List<CompiledMatcher> children;
        private final String cacheKey;

        Any(List<CompiledMatcher> children, String cacheKey) {
            this.children = List.copyOf(children);
            this.cacheKey = cacheKey;
        }

        @Override
        public boolean matches(EvalContext context) {
            for (CompiledMatcher child : children) {
                if (child.matches(context)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public MatchExplanation explain(EvalContext context) {
            List<MatchExplanation> explained = explainAll(children, context);
            return new MatchExplanation("any", explained.stream().anyMatch(MatchExplanation::passed),
                    null, null, explained);
        }

        @Override
        public String cacheKey() {
            return cacheKey;
        }
    }

    static final class Not implements CompiledMatcher {

        private final CompiledMatcher child;
        private final String cacheKey;

        Not(CompiledMatcher child, String cacheKey) {
            this.child = child;
            this.cacheKey = cacheKey;
        }

        @Override
        public boolean matches(EvalContext context) {
            return !child.matches(context);
        }

        @Override
        public MatchExplanation explain(EvalContext context) {
            MatchExplanation explained = child.explain(context);
            return new MatchExplanation("not", !explained.passed(), null, null, List.of(explained));
        }

        @Override
        public String cacheKey() {
            return cacheKey;
        }
    }

    static final class Leaf implements CompiledMatcher {

        private final ValueExtractor extractor;
        private final CompiledPredicate predicate;
        private final MatchNode.Quantifier quantifier;
        private final boolean resultWhenMissing;
        private final int maxFieldValueLength;
        private final String description;
        private final String cacheKey;

        Leaf(ValueExtractor extractor, CompiledPredicate predicate, MatchNode.Quantifier quantifier,
             boolean resultWhenMissing, int maxFieldValueLength, String description, String cacheKey) {
            this.extractor = extractor;
            this.predicate = predicate;
            this.quantifier = quantifier;
            this.resultWhenMissing = resultWhenMissing;
            this.maxFieldValueLength = maxFieldValueLength;
            this.description = description;
            this.cacheKey = cacheKey;
        }

        @Override
        public boolean matches(EvalContext context) {
            return context.memoized(cacheKey, () -> decide(extractor.extract(context.request())));
        }

        @Override
        public MatchExplanation explain(EvalContext context) {
            Extracted extracted = extractor.extract(context.request());
            boolean passed = context.memoized(cacheKey, () -> decide(extracted));
            return new MatchExplanation("leaf", passed, description, summarize(extracted), List.of());
        }

        @Override
        public String cacheKey() {
            return cacheKey;
        }

        private boolean decide(Extracted extracted) {
            if (extracted.state() == Extracted.State.UNAVAILABLE) {
                return false;
            }
            if (extracted.state() == Extracted.State.MISSING) {
                return resultWhenMissing;
            }
            boolean anyUsable = false;
            for (Value value : extracted.values()) {
                if (value.text() != null && value.text().length() > maxFieldValueLength) {
                    continue;
                }
                anyUsable = true;
                boolean passed = predicate.test(value);
                if (quantifier == MatchNode.Quantifier.ANY && passed) {
                    return true;
                }
                if (quantifier == MatchNode.Quantifier.ALL && !passed) {
                    return false;
                }
            }
            // ANY：沒有任何值符合；ALL：所有可用值都符合（但至少要有一個可用值）。
            return quantifier == MatchNode.Quantifier.ALL && anyUsable;
        }

        private static String summarize(Extracted extracted) {
            if (extracted.state() != Extracted.State.FOUND) {
                return extracted.state().name().toLowerCase(Locale.ROOT);
            }
            List<Value> values = extracted.values();
            List<String> shown = new ArrayList<>();
            for (int i = 0; i < values.size() && i < MAX_ACTUAL_VALUES_SHOWN; i++) {
                shown.add(quote(values.get(i).text()));
            }
            if (values.size() == 1) {
                return shown.get(0);
            }
            String more = values.size() > MAX_ACTUAL_VALUES_SHOWN
                    ? ", … (+" + (values.size() - MAX_ACTUAL_VALUES_SHOWN) + ")" : "";
            return "[" + String.join(", ", shown) + more + "]";
        }

        private static String quote(String text) {
            if (text == null) {
                return "null";
            }
            String shown = text.length() > MAX_ACTUAL_TEXT_LENGTH
                    ? text.substring(0, MAX_ACTUAL_TEXT_LENGTH) + "…" : text;
            return "\"" + shown + "\"";
        }
    }

    private static List<MatchExplanation> explainAll(List<CompiledMatcher> children, EvalContext context) {
        List<MatchExplanation> explained = new ArrayList<>(children.size());
        for (CompiledMatcher child : children) {
            explained.add(child.explain(context));
        }
        return explained;
    }
}
