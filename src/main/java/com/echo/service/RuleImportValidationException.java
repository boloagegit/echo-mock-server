package com.echo.service;

import com.echo.dto.RuleImportPreview.Problem;

import java.util.List;

/** Validation failure before an atomic import can publish any new rule or response. */
public final class RuleImportValidationException extends IllegalArgumentException {
    private final List<Problem> errors;

    public RuleImportValidationException(List<Problem> errors) {
        super("RULE_IMPORT_INVALID");
        this.errors = List.copyOf(errors);
    }

    public List<Problem> errors() {
        return errors;
    }
}
