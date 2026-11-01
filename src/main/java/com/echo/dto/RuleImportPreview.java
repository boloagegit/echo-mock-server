package com.echo.dto;

import com.echo.entity.Protocol;

import java.util.List;

/**
 * A read-only preview; connection selection values include the current destination revision.
 *
 * <p>{@code enabled} and {@code disabled} count only included rows without content errors, so they
 * match what a confirmation would create once every connection is chosen.
 */
public record RuleImportPreview(
        int total,
        int enabled,
        int disabled,
        List<ConnectionRequirement> requirements,
        List<ConnectionOption> connections,
        List<Problem> errors,
        List<RowSummary> rows) {
    /**
     * A source forwarding destination that needs a destination choice.
     *
     * @param mode the source forwarding mode (ORIGINAL_HOST, DEFAULT_CONNECTION or CONNECTION)
     * @param sourceName the source connection's display name when the export carried one
     * @param rows 1-based rows that use this source connection
     * @param suggestion a destination selection the UI may preselect; the user still confirms it
     */
    public record ConnectionRequirement(
            String key,
            Protocol protocol,
            String mode,
            String sourceName,
            int rules,
            List<Integer> rows,
            String suggestion) {}

    public record ConnectionOption(
            String selection, Protocol protocol, String mode, String id, String name) {}

    /** {@code message} is a technical detail; clients localize by {@code code}. */
    public record Problem(int row, String code, String message) {}

    /**
     * One rule from the file, for review before import.
     *
     * @param action MOCK, FORWARD or FAULT
     * @param connectionKey the requirement key for forwarding rules, otherwise null
     * @param duplicate an existing destination rule has the same protocol, host, key, method and conditions
     */
    public record RowSummary(
            int row,
            Protocol protocol,
            String method,
            String matchKey,
            String targetHost,
            String description,
            boolean enabled,
            String action,
            String connectionKey,
            boolean duplicate) {}
}
