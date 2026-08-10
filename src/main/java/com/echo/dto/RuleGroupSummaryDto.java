package com.echo.dto;

import java.util.List;
import java.util.Map;

/** Tag-group counts for the lazy-loaded grouped rule view. */
public record RuleGroupSummaryDto(
        Map<String, List<String>> tagKeys,
        Map<String, Long> counts,
        long totalElements) {
}
