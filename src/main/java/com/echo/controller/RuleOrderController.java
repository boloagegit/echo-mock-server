package com.echo.controller;

import com.echo.service.RuleOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 規則的判斷順序與一定輪不到的規則（唯讀，與規則內容相同的存取權限）。 */
@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class RuleOrderController {

    private final RuleOrderService ruleOrderService;

    /** 所有一定輪不到的規則，供規則列表標示。 */
    @GetMapping("/order-issues")
    public ResponseEntity<RuleOrderService.IssuesView> issues() {
        return ResponseEntity.ok(ruleOrderService.issues());
    }

    /** 這條規則的判斷順序：會一起考慮的規則，依實際檢查順序排列。 */
    @GetMapping("/{id}/order")
    public ResponseEntity<RuleOrderService.RuleOrderView> order(@PathVariable String id) {
        return ruleOrderService.orderFor(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
