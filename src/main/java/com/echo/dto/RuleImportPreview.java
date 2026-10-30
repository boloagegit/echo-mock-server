package com.echo.dto;

import com.echo.entity.Protocol;

import java.util.List;

/** A read-only preview; connection selection values include the current destination revision. */
public record RuleImportPreview(
        int total,
        int enabled,
        int disabled,
        List<ConnectionRequirement> requirements,
        List<ConnectionOption> connections,
        List<Problem> errors) {
    public record ConnectionRequirement(
            String key, Protocol protocol, String sourceName, int rules) {}

    public record ConnectionOption(
            String selection, Protocol protocol, String mode, String id, String name) {}

    public record Problem(int row, String code, String message) {}
}
