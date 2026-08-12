package com.echo.dto;

import java.util.List;

/** Stable JSON shape for the admin rule list's server-side pagination. */
public record RulePageDto(
        List<RuleDto> results,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
