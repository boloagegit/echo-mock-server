package com.echo.service;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Read-only candidate rules with the condition handles already owned by their
 * originating snapshot. Filtering must move entries together; this is not a
 * second condition registry and never stores request evaluation state.
 */
public final class RuleCandidates<T> extends AbstractList<T> implements RandomAccess {

    public record Entry<T>(
            T rule, ConditionMatcher.RuleConditions conditions, ResponseRevision responseRevision) {
        public Entry(T rule, ConditionMatcher.RuleConditions conditions) {
            this(rule, conditions, null);
        }

        public Entry {
            Objects.requireNonNull(rule, "rule");
            // Null retains compatibility with snapshot tools that have no matcher.
        }
    }

    private final List<Entry<T>> entries;

    private RuleCandidates(List<Entry<T>> entries) {
        this.entries = List.copyOf(entries);
    }

    public static <T> RuleCandidates<T> ofEntries(List<Entry<T>> entries) {
        return new RuleCandidates<>(entries);
    }

    public List<Entry<T>> entries() {
        return entries;
    }

    public Entry<T> entry(int index) {
        return entries.get(index);
    }

    @Override
    public T get(int index) {
        return entries.get(index).rule();
    }

    @Override
    public int size() {
        return entries.size();
    }
}
