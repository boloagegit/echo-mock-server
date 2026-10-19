package com.echo.controller;

import com.echo.service.RuleEditService;
import com.echo.service.RuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Rule status: enable/disable, protect, extend retention period, can be for a single rule, multiple rules or rules for the same label.
 */
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class RuleStateController {

    private final RuleService ruleService;
    private final RuleEditService ruleEditService;

    @PutMapping("/rules/{id}/enable")
    public ResponseEntity<?> enableRule(@PathVariable String id) {
        return ruleEditService.findDto(id)
                .map(dto -> {
                    ruleService.updateEnabled(List.of(id), true);
                    return ResponseEntity.ok(ruleEditService.findDto(id).orElse(dto));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/rules/{id}/disable")
    public ResponseEntity<?> disableRule(@PathVariable String id) {
        return ruleEditService.findDto(id)
                .map(dto -> {
                    ruleService.updateEnabled(List.of(id), false);
                    return ResponseEntity.ok(ruleEditService.findDto(id).orElse(dto));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/rules/batch/enable")
    public ResponseEntity<?> enableRules(@RequestBody List<String> ids) {
        int updated = ruleService.updateEnabled(ids, true);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @PutMapping("/rules/batch/disable")
    public ResponseEntity<?> disableRules(@RequestBody List<String> ids) {
        int updated = ruleService.updateEnabled(ids, false);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @PutMapping("/rules/batch/protect")
    public ResponseEntity<?> protectRules(@RequestBody List<String> ids) {
        int updated = ruleService.updateProtected(ids, true);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @PutMapping("/rules/batch/unprotect")
    public ResponseEntity<?> unprotectRules(@RequestBody List<String> ids) {
        int updated = ruleService.updateProtected(ids, false);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    // When the target state is already reached, the number of updates is 0, but the rule exists and the operation is still successful (repeated operations are not considered errors).
    @PutMapping("/rules/{id}/protect")
    public ResponseEntity<?> protectRule(@PathVariable String id) {
        if (ruleEditService.findDto(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ruleService.updateProtected(List.of(id), true);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PutMapping("/rules/{id}/unprotect")
    public ResponseEntity<?> unprotectRule(@PathVariable String id) {
        if (ruleEditService.findDto(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ruleService.updateProtected(List.of(id), false);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PutMapping("/rules/{id}/extend")
    public ResponseEntity<?> extendRule(@PathVariable String id) {
        return ruleService.extendRule(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @PutMapping("/rules/batch/extend")
    public ResponseEntity<?> extendRules(@RequestBody List<String> ids) {
        int updated = ruleService.extendRules(ids);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @PutMapping("/rules/tag/{key}/{value}/enable")
    public ResponseEntity<?> enableByTag(@PathVariable String key, @PathVariable String value) {
        List<String> ids = ruleService.findIdsByTag(key, value);
        int updated = ruleService.updateEnabled(ids, true);
        return ResponseEntity.ok(Map.of("updated", updated, "ids", ids));
    }

    @PutMapping("/rules/tag/{key}/{value}/disable")
    public ResponseEntity<?> disableByTag(@PathVariable String key, @PathVariable String value) {
        List<String> ids = ruleService.findIdsByTag(key, value);
        int updated = ruleService.updateEnabled(ids, false);
        return ResponseEntity.ok(Map.of("updated", updated, "ids", ids));
    }
}
