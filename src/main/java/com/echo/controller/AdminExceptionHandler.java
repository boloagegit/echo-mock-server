package com.echo.controller;

import com.echo.service.RuleApplyValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/** Error response from the management background API: 400 and error message will be returned when the input is incorrect. It only applies to the following controller and does not affect the mock request. */
@RestControllerAdvice(assignableTypes = {
        SystemController.class,
        RuleController.class,
        RuleApplyController.class,
        RuleStateController.class,
        ImportExportController.class,
        ResponseController.class,
        RequestLogController.class,
        AuditController.class,
        ScenarioController.class,
})
public class AdminExceptionHandler {

    @ExceptionHandler(RuleApplyValidationException.class)
    public ResponseEntity<Map<String, Object>> handleRuleApplyValidation(RuleApplyValidationException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.getMessage());
        body.put("validationCode", e.getValidationCode());
        body.put("path", e.getPath());
        body.putAll(e.getDetails());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
