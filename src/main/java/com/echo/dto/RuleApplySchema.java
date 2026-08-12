package com.echo.dto;

import java.util.List;

/** Machine-readable contract used by both declarative-rule validation and field guidance. */
public record RuleApplySchema(
        String apiVersion,
        String kind,
        List<Field> fields) {

    public record Field(
            String path,
            String type,
            List<String> allowedValues,
            Long minimum,
            Long maximum,
            Integer maxLength,
            Object defaultValue,
            List<String> protocols,
            List<String> actions,
            String requiredWhen,
            String valueType,
            boolean readOnly) {
    }
}
