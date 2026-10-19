package com.echo.controller;

import com.echo.dto.RuleDto;
import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.service.ResponseService;
import com.echo.service.RuleAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Query, create, modify, delete and extend the storage period of shared responses (multiple rules can use the same response content).
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class ResponseController {

    private final ResponseService responseService;
    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final Optional<RuleAuditService> ruleAuditService;

    @GetMapping("/responses")
    public ResponseEntity<List<Response>> listResponses(@RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(responseService.search(keyword));
    }

    @GetMapping(value = "/responses/summary", params = "!page")
    public ResponseEntity<List<ResponseService.ResponseSummary>> listResponseSummary() {
        return ResponseEntity.ok(responseService.findAllWithUsage());
    }

    @GetMapping(value = "/responses/summary", params = "page")
    public ResponseEntity<ResponseService.ResponseSummaryPage> listResponseSummaryPage(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String usage,
            @RequestParam(required = false) String contentType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return ResponseEntity.ok(responseService.findSummaryPage(
                keyword, usage, contentType, page, size, sort, direction));
    }

    @GetMapping("/responses/{id}")
    public ResponseEntity<Response> getResponseById(@PathVariable Long id) {
        return responseService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/responses/{id}/rules")
    public ResponseEntity<List<RuleDto>> getRulesByResponseId(@PathVariable Long id) {
        List<RuleDto> rules = protocolHandlerRegistry.findByResponseId(id).stream()
                .map(r -> protocolHandlerRegistry.toDto(r, null))
                .toList();
        return ResponseEntity.ok(rules);
    }

    @PostMapping("/responses")
    public ResponseEntity<Response> createResponse(@RequestBody Response response) {
        response.setId(null);
        Response saved = responseService.save(response);
        ruleAuditService.ifPresent(s -> s.logResponseCreate(saved));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/responses/{id}")
    public ResponseEntity<?> updateResponse(@PathVariable Long id, @RequestBody Response response) {
        return responseService.findById(id)
                .map(existing -> {
                    // The version sent is different from the current version, which means that the response has been modified during editing: reject to avoid covering up other people's modifications.
                    // Callers with undelivered versions (old scripts) remain updated with the current version.
                    if (response.getVersion() != null && !response.getVersion().equals(existing.getVersion())) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                                "error", "OPTIMISTIC_LOCK_CONFLICT",
                                "currentVersion", existing.getVersion()));
                    }
                    Response before = Response.builder().id(existing.getId()).description(existing.getDescription())
                            .contentType(existing.getContentType()).body(existing.getBody()).build();
                    response.setId(id);
                    response.setVersion(existing.getVersion());
                    // Unsent fields retain their original values (the description or type will not be cleared when only changing the content)
                    if (response.getDescription() == null) response.setDescription(existing.getDescription());
                    if (response.getContentType() == null) response.setContentType(existing.getContentType());
                    Response saved;
                    try {
                        saved = responseService.save(response);
                    } catch (ObjectOptimisticLockingFailureException e) {
                        // Another caller saves between reading the version and writing the update.
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("error", "OPTIMISTIC_LOCK_CONFLICT"));
                    }
                    ruleAuditService.ifPresent(s -> s.logResponseUpdate(before, saved));
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/responses/{id}")
    public ResponseEntity<?> deleteResponse(@PathVariable Long id) {
        var existing = responseService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        int rulesDeleted = responseService.deleteWithRules(id);
        ruleAuditService.ifPresent(s -> s.logResponseDelete(existing.get()));
        return ResponseEntity.ok(Map.of("deletedRules", rulesDeleted));
    }

    @DeleteMapping("/responses/batch")
    public ResponseEntity<?> deleteResponses(@RequestBody List<Long> ids) {
        int deleted = 0, deletedRules = 0;
        for (Long id : ids) {
            try {
                deletedRules += responseService.deleteWithRules(id);
                deleted++;
            } catch (Exception e) {
                log.warn("Failed to delete response {}: {}", id, e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of("deleted", deleted, "deletedRules", deletedRules));
    }

    @DeleteMapping("/responses/all")
    public ResponseEntity<?> deleteAllResponses() {
        var result = responseService.deleteAll();
        return ResponseEntity.ok(Map.of("deletedResponses", result.deletedResponses(), "deletedRules", result.deletedRules()));
    }

    @DeleteMapping("/responses/orphans")
    public ResponseEntity<?> deleteOrphanResponses() {
        int deleted = responseService.deleteOrphanResponses();
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @GetMapping("/responses/orphan-count")
    public ResponseEntity<?> countOrphanResponses() {
        return ResponseEntity.ok(Map.of("count", responseService.countOrphanResponses()));
    }

    @PutMapping("/responses/{id}/extend")
    public ResponseEntity<?> extendResponse(@PathVariable Long id) {
        return responseService.extendResponse(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @PutMapping("/responses/batch/extend")
    public ResponseEntity<?> extendResponses(@RequestBody List<Long> ids) {
        int updated = responseService.extendResponses(ids);
        return ResponseEntity.ok(Map.of("updated", updated));
    }
}
