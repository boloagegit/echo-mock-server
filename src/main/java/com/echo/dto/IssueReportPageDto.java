package com.echo.dto;

import com.echo.entity.IssueReport;

import java.util.List;

/** Bounded issue-report list response. */
public record IssueReportPageDto(
        List<IssueReport> results,
        int page,
        int size,
        long totalElements,
        int totalPages,
        long openCount
) {
    public IssueReportPageDto {
        results = List.copyOf(results);
    }
}
