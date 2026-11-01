package com.echo.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rules to copy, with explicit choices from the destination environment's preview.
 *
 * @param excludedRows 1-based row numbers the user unchecked; they are neither validated nor imported.
 */
public record RuleImportRequest(
        List<RuleDto> rules, Map<String, String> connectionMappings, Set<Integer> excludedRows) {
    public RuleImportRequest(List<RuleDto> rules, Map<String, String> connectionMappings) {
        this(rules, connectionMappings, Set.of());
    }
}
