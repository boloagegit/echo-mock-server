package com.echo.controller;

import com.echo.service.RuleOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only rule evaluation order and unreachable rules, using the same access permissions as rule contents. */
@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class RuleOrderController {

    private final RuleOrderService ruleOrderService;

    /** All definitely unreachable rules, for indicators in the rule list. */
    @GetMapping("/order-issues")
    public ResponseEntity<RuleOrderService.IssuesView> issues() {
        return ResponseEntity.ok(ruleOrderService.issues());
    }

    /** The judgment order of this rule: the rules that will be considered together are arranged in the actual checking order. */
    @GetMapping("/{id}/order")
    public ResponseEntity<RuleOrderService.RuleOrderView> order(@PathVariable String id) {
        return ruleOrderService.orderFor(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
