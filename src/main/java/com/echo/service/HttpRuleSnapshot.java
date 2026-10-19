package com.echo.service;

import com.echo.entity.HttpRule;
import com.echo.protocol.http.HttpPathPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable snapshot of all enabled HTTP rules at a point in time, stored as one entry in the {@code httpRules} cache.
 * Existing invalidation paths, including rule changes, Response deletion, scheduled cleanup, and cross-instance synchronization, evict it too.
 * <p>
 * Compute and retain candidates for each (host, method) on first use. Host or method values absent from all rules map to shared
 * keys, since their result depends only on rules without a host/method constraint. Snapshot size therefore grows only with rule count,
 * rather than unbounded client-supplied {@code X-Original-Host} or method values.
 * <p>
 * Index each candidate set by path: use direct lookups for exact paths, and evaluate {@code *} and {@code /x/*} patterns individually.
 * Merge matches in the original priority order. Regardless of total rules for the host and method, lookup cost depends only on matching rules.
 */
final class HttpRuleSnapshot {

    /** Sentinel host/method absent from every rule; valid hosts and methods cannot contain NUL. */
    private static final String UNLISTED = "\u0000";

    private final List<HttpRule> rules;
    private final Set<String> hosts;
    /** Distinct original method strings present in rules, usually only a few. */
    private final List<String> methods;
    private final Comparator<HttpRule> order;
    /** Retain strong references so compiled conditions live as long as the snapshot. */
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final List<ConditionMatcher.RuleConditions> pinnedConditions;
    private final Map<Key, Candidates> candidates = new ConcurrentHashMap<>();

    /**
     * @param rules      Enabled rules with valid Responses, ordered by id as in the original query.
     * @param order      Prioritization of candidate rules
     * @param conditions Resolve and retain each rule's condition object; null disables retention.
     */
    HttpRuleSnapshot(List<HttpRule> rules, Comparator<HttpRule> order, ConditionMatcher conditions) {
        this.rules = List.copyOf(rules);
        this.order = order;
        Set<String> ruleHosts = new HashSet<>();
        Set<String> ruleMethods = new LinkedHashSet<>();
        List<ConditionMatcher.RuleConditions> pinned = new ArrayList<>(rules.size());
        for (HttpRule rule : this.rules) {
            if (rule.getTargetHost() != null && !rule.getTargetHost().isEmpty()) {
                ruleHosts.add(rule.getTargetHost());
            }
            if (rule.getMethod() != null) {
                ruleMethods.add(rule.getMethod());
            }
            if (conditions != null) {
                ConditionMatcher.RuleConditions ruleConditions = conditions.conditions(rule.getMatchSpec(),
                        rule.getBodyCondition(), rule.getQueryCondition(), rule.getHeaderCondition());
                if (ruleConditions != null) {
                    pinned.add(ruleConditions);
                }
            }
        }
        this.hosts = Set.copyOf(ruleHosts);
        this.methods = List.copyOf(ruleMethods);
        this.pinnedConditions = List.copyOf(pinned);
    }

    /**
     * Match the original {@code findByHostAndMethod} and sorting behavior: exact host or no host constraint,
     * and a case-insensitive method match or no method constraint; do not filter paths here.
     */
    List<HttpRule> candidates(String host, String method) {
        return candidateSet(host, method).ordered();
    }

    /**
     * Filter {@link #candidates(String, String)} by request path without changing order;
     * results match applying {@link HttpPathPattern#matches} to every candidate.
     */
    List<HttpRule> candidates(String host, String method, String path) {
        return candidateSet(host, method).forPath(path);
    }

    private Candidates candidateSet(String host, String method) {
        String hostKey = host != null && hosts.contains(host) ? host : UNLISTED;
        return candidates.computeIfAbsent(new Key(hostKey, methodKey(method)),
                key -> new Candidates(compute(host, method)));
    }

    int ruleCount() {
        return rules.size();
    }

    /** All snapshot rules, enabled with valid Responses and ordered by id. */
    List<HttpRule> rules() {
        return rules;
    }

    /** Number of computed candidate sets, exposed for tests proving arbitrary inputs cannot grow it without bound. */
    int candidateSetCount() {
        return candidates.size();
    }

    private List<HttpRule> compute(String host, String method) {
        List<HttpRule> result = new ArrayList<>();
        for (HttpRule rule : rules) {
            boolean hostMatches = rule.getTargetHost() == null || rule.getTargetHost().isEmpty()
                    || rule.getTargetHost().equals(host);
            boolean methodMatches = rule.getMethod() == null || rule.getMethod().equalsIgnoreCase(method);
            if (hostMatches && methodMatches) {
                result.add(rule);
            }
        }
        result.sort(order);
        return List.copyOf(result);
    }

    /**
     * Results depend only on which rule methods equal the request method ignoring case; use that set of methods as the key,
     * preserving {@code equalsIgnoreCase} behavior without adding keys for arbitrary method strings.
     */
    private String methodKey(String method) {
        if (method == null) {
            return UNLISTED;
        }
        String single = null;
        StringBuilder several = null;
        for (String ruleMethod : methods) {
            if (!ruleMethod.equalsIgnoreCase(method)) {
                continue;
            }
            if (single == null) {
                single = ruleMethod;
            } else {
                if (several == null) {
                    several = new StringBuilder(single);
                }
                several.append(UNLISTED).append(ruleMethod);
            }
        }
        if (several != null) {
            return several.toString();
        }
        return single != null ? single : UNLISTED;
    }

    private record Key(String host, String method) {
    }

    /** A sorted set of candidate rules and its path index; it does not change after creation. */
    private static final class Candidates {

        private static final int[] NONE = new int[0];

        private final List<HttpRule> ordered;
        /** Exact path to rule positions in {@link #ordered}, in increasing order. */
        private final Map<String, int[]> exact;
        /** Positions of {@code *} and {@code /x/*} rules, in increasing order; omit pathless rules because they cannot match any request. */
        private final int[] patterns;

        Candidates(List<HttpRule> ordered) {
            this.ordered = ordered;
            Map<String, List<Integer>> byPath = new HashMap<>();
            List<Integer> patternPositions = new ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) {
                String matchKey = ordered.get(i).getMatchKey();
                if (HttpPathPattern.isExact(matchKey)) {
                    byPath.computeIfAbsent(matchKey, path -> new ArrayList<>()).add(i);
                } else if (matchKey != null) {
                    patternPositions.add(i);
                }
            }
            Map<String, int[]> index = new HashMap<>(byPath.size() * 2);
            byPath.forEach((path, positions) -> index.put(path, toArray(positions)));
            this.exact = Map.copyOf(index);
            this.patterns = toArray(patternPositions);
        }

        List<HttpRule> ordered() {
            return ordered;
        }

        List<HttpRule> forPath(String path) {
            int[] exactPositions = path == null ? NONE : exact.getOrDefault(path, NONE);
            List<HttpRule> result = new ArrayList<>(exactPositions.length + patterns.length);
            int e = 0;
            int p = 0;
            while (e < exactPositions.length || p < patterns.length) {
                if (p == patterns.length || (e < exactPositions.length && exactPositions[e] < patterns[p])) {
                    result.add(ordered.get(exactPositions[e++]));
                } else {
                    HttpRule rule = ordered.get(patterns[p++]);
                    if (HttpPathPattern.matches(rule.getMatchKey(), path)) {
                        result.add(rule);
                    }
                }
            }
            return Collections.unmodifiableList(result);
        }

        private static int[] toArray(List<Integer> positions) {
            int[] array = new int[positions.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = positions.get(i);
            }
            return array;
        }
    }
}
