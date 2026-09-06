package com.echo.dto;

import java.util.List;

/** Bounded account-management list response. */
public record BuiltinUserPageDto(
        List<BuiltinUserSummaryDto> results,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public BuiltinUserPageDto {
        results = List.copyOf(results);
    }
}
