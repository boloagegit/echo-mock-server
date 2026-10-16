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
    /** 選中的是沒有條件的備選規則（條件都不成立時才會選它）。 */
    private final boolean byFallback;

    public MatchResult(T matchedRule, List<MatchChainEntry> matchChain) {
        this(matchedRule, matchChain, false);
    }

    public boolean isMatched() {
        return matchedRule != null;
    }
}
