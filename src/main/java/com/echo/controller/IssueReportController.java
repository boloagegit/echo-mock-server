package com.echo.controller;

import com.echo.entity.IssueReport;
import com.echo.entity.IssueReport.IssueStatus;
import com.echo.service.IssueReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Issue Report REST API
 * <p>
 * 建立/查看：任何登入使用者
 * 回覆/resolve/reopen/刪除：僅 ADMIN
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/admin/issues", produces = "application/json")
@RequiredArgsConstructor
public class IssueReportController {

    private final IssueReportService issueReportService;

    @GetMapping
    public ResponseEntity<List<IssueReport>> list(
            @RequestParam(required = false) String status) {
        if (status != null) {
            try {
                IssueStatus s = IssueStatus.valueOf(status.toUpperCase(Locale.ROOT));
                return ResponseEntity.ok(issueReportService.findByStatus(s));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.ok(issueReportService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<IssueReport> getById(@PathVariable String id) {
        return issueReportService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> countOpen() {
        return ResponseEntity.ok(Map.of("open", issueReportService.countOpen()));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body,
                                    @AuthenticationPrincipal UserDetails user) {
        String title = body.get("title");
        String description = body.get("description");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "TITLE_REQUIRED"));
        }
        if (title.length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("error", "TITLE_TOO_LONG"));
        }
        if (description == null || description.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "DESCRIPTION_REQUIRED"));
        }
        IssueReport issue = issueReportService.create(title, description, user.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(issue);
    }

    @PutMapping("/{id}/reply")
    public ResponseEntity<?> reply(@PathVariable String id,
                                   @RequestBody Map<String, String> body,
                                   @AuthenticationPrincipal UserDetails user) {
        String reply = body.get("reply");
        if (reply == null || reply.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "REPLY_REQUIRED"));
        }
        return handleRequest(() -> ResponseEntity.ok(
                issueReportService.reply(id, reply, user.getUsername())));
    }

    @PutMapping("/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable String id,
                                     @AuthenticationPrincipal UserDetails user) {
        return handleRequest(() -> ResponseEntity.ok(
                issueReportService.resolve(id, user.getUsername())));
    }

    @PutMapping("/{id}/reopen")
    public ResponseEntity<?> reopen(@PathVariable String id) {
        return handleRequest(() -> ResponseEntity.ok(
                issueReportService.reopen(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return handleRequest(() -> {
            issueReportService.delete(id);
            return ResponseEntity.ok(Map.of("deleted", true));
        });
    }

    private ResponseEntity<?> handleRequest(RequestAction action) {
        try {
            return action.execute();
        } catch (IllegalArgumentException e) {
            if ("ISSUE_NOT_FOUND".equals(e.getMessage())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "OPTIMISTIC_LOCK_CONFLICT"));
        }
    }

    @FunctionalInterface
    private interface RequestAction {
        ResponseEntity<?> execute();
    }
}
