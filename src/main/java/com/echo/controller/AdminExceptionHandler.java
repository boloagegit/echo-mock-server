package com.echo.controller;

import com.echo.service.RuleApplyValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/** 管理後台 API 的錯誤回應：輸入不正確時回 400 與錯誤訊息。只套用在下列 controller，不影響 mock 請求。 */
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
