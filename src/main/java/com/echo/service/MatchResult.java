package com.echo.service;

import com.echo.entity.BaseRule;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class MatchResult<T extends BaseRule> {
    private final T matchedRule;
    private final List<MatchChainEntry> matchChain;
    /** The selected rule is an alternative rule without conditions (it will be selected only when none of the conditions are met). */
    private final boolean byFallback;

    @com.fasterxml.jackson.annotation.JsonIgnore private final ResponseRevision responseRevision;

    public MatchResult(T rule, List<MatchChainEntry> chain, boolean fallback) {
        this(rule, chain, fallback, null);
    }

    public MatchResult(T matchedRule, List<MatchChainEntry> matchChain) {
        this(matchedRule, matchChain, false);
    }

    public boolean isMatched() {
        return matchedRule != null;
    }
}
