package com.echo.controller;

import com.echo.entity.RuleAuditLog;
import com.echo.service.RuleAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 稽核紀錄：規則與回應的建立、修改、刪除歷程。稽核功能關閉時回傳空的結果。
 */
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class AuditController {

    private final Optional<RuleAuditService> ruleAuditService;

    @GetMapping("/rules/{id}/audit")
    public ResponseEntity<List<RuleAuditLog>> getRuleAuditLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limit) {
        return ruleAuditService
                .map(s -> ResponseEntity.ok(s.getAuditLogs(id, limit)))
                .orElse(ResponseEntity.ok(List.of()));
    }

    @GetMapping(value = "/audit", params = {"!page", "!detailId"})
    public ResponseEntity<List<RuleAuditLog>> getAllAuditLogs(
            @RequestParam(defaultValue = "1000") int limit) {
        return ruleAuditService
                .map(s -> ResponseEntity.ok(s.getAllAuditLogs(limit)))
                .orElse(ResponseEntity.ok(List.of()));
    }

    @GetMapping(value = "/audit", params = "page")
    public ResponseEntity<RuleAuditService.AuditQueryResult> queryAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false, name = "sort") String sortField,
            @RequestParam(required = false) String direction) {
        RuleAuditService.AuditQueryFilter filter = RuleAuditService.AuditQueryFilter.builder()
                .action(action == null || action.isBlank() ? null : RuleAuditLog.Action.valueOf(action))
                .operator(operator)
                .keyword(keyword)
                .targetType(type)
                .page(page)
                .size(size)
                .sortField(sortField)
                .sortDirection(direction)
                .build();
        return ruleAuditService
                .map(s -> ResponseEntity.ok(s.queryAuditSummary(filter)))
                .orElse(ResponseEntity.ok(RuleAuditService.AuditQueryResult.builder()
                        .results(List.of()).page(0).size(size).totalElements(0).totalPages(0).build()));
    }

    @GetMapping(value = "/audit", params = "detailId")
    public ResponseEntity<RuleAuditLog> getAuditLogDetail(@RequestParam Long detailId) {
        return ruleAuditService
                .map(s -> Optional.ofNullable(s.getAuditDetail(detailId))
                        .map(ResponseEntity::ok)
                        .orElse(ResponseEntity.notFound().build()))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/audit/all")
    public ResponseEntity<Map<String, Object>> deleteAllAuditLogs() {
        long deleted = ruleAuditService.map(s -> s.deleteAll()).orElse(0L);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }
}
