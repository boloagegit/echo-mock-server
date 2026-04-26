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

    public boolean isMatched() {
        return matchedRule != null;
    }
}
