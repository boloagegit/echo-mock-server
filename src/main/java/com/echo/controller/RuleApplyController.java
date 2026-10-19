package com.echo.controller;

import com.echo.dto.RuleApplyDocument;
import com.echo.dto.RuleApplyResult;
import com.echo.dto.RuleApplySchema;
import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.service.ResponseService;
import com.echo.service.RuleApplyContractService;
import com.echo.service.RuleApplyMapper;
import com.echo.service.RuleApplyPersistenceSynchronizer;
import com.echo.service.RuleAuditService;
import com.echo.service.RuleEditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Declarative rule files (echo.mock/v1, v2):
 * <ul>
 *   <li>GET /api/admin/rules/schema - File format</li>
 *   <li>GET /api/admin/rules/{id}/manifest - Get rule  in file format</li>
 *   <li>POST /api/admin/rules/apply, PUT /api/admin/rules/{id}/apply - Create or update rule  with complete file</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class RuleApplyController {

    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ResponseService responseService;
    private final RuleEditService ruleEditService;
    private final Optional<RuleAuditService> ruleAuditService;
    private final RuleApplyMapper ruleApplyMapper;
    private final RuleApplyPersistenceSynchronizer ruleApplyPersistenceSynchronizer;
    private final RuleApplyContractService ruleApplyContractService;

    @GetMapping("/rules/{id}/manifest")
    public ResponseEntity<RuleApplyDocument> getRuleManifest(@PathVariable String id) {
        return ruleEditService.findDto(id, true)
                .map(ruleApplyMapper::fromRuleDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Returns the machine-readable contract used by the declarative editor and server validator. */
    @GetMapping("/rules/schema")
    public RuleApplySchema getRuleApplySchema(@RequestParam(required = false) String apiVersion) {
        return ruleApplyContractService.schema(apiVersion);
    }

    /** Creates a rule from a complete declarative JSON document. */
    @PostMapping("/rules/apply")
    @Transactional
    public ResponseEntity<?> applyRule(@RequestBody RuleApplyDocument document) {
        ruleApplyContractService.validateForCreate(document);
        RuleDto dto = ruleApplyMapper.toRuleDto(document);
        ruleEditService.requireProtocolEnabled(dto.getProtocol());

        dto.setId(null);
        dto.setVersion(null);
        prepareApplyResponse(dto);
        RuleEditService.Saved saved = ruleEditService.save(dto);
        ruleAuditService.ifPresent(service -> service.logCreate(saved.rule()));
        ruleApplyPersistenceSynchronizer.flush();
        return applyResult(HttpStatus.CREATED, "CREATED", saved.rule().getId());
    }

    /** Updates the path-bound rule from a complete declarative JSON document. */
    @PutMapping("/rules/{id}/apply")
    @Transactional
    public ResponseEntity<?> applyRule(@PathVariable String id, @RequestBody RuleApplyDocument document) {
        ruleApplyContractService.validateForUpdate(document, id);
        RuleDto dto = ruleApplyMapper.toRuleDto(document);
        ruleEditService.requireProtocolEnabled(dto.getProtocol());

        RuleApplyDocument.Metadata metadata = document.getMetadata();
        Long requestedVersion = metadata.getResourceVersion();
        Optional<? extends BaseRule> existing = protocolHandlerRegistry.findById(id);

        if (existing.isPresent()) {
            BaseRule current = existing.get();
            if (!Objects.equals(requestedVersion, current.getVersion())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "RESOURCE_VERSION_CONFLICT",
                        "currentResourceVersion", current.getVersion()));
            }
            if (current.getProtocol() != dto.getProtocol()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "PROTOCOL_IMMUTABLE",
                        "currentProtocol", current.getProtocol().name()));
            }

            dto.setId(id);
            dto.setVersion(current.getVersion());
            dto.setCreatedAt(current.getCreatedAt());
            prepareApplyResponse(dto);
            String beforeJson = ruleAuditService.map(service -> service.snapshot(current)).orElse(null);
            try {
                RuleEditService.Saved saved = ruleEditService.save(dto);
                ruleAuditService.ifPresent(service -> {
                    if (beforeJson != null) {
                        service.logUpdate(beforeJson, service.snapshot(saved.rule()));
                    } else {
                        service.logUpdate(current, saved.rule());
                    }
                });
                ruleApplyPersistenceSynchronizer.flush();
                return applyResult(HttpStatus.OK, "UPDATED", saved.rule().getId());
            } catch (ObjectOptimisticLockingFailureException e) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "OPTIMISTIC_LOCK_CONFLICT"));
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "RESOURCE_NOT_FOUND"));
    }

    private ResponseEntity<RuleApplyResult> applyResult(HttpStatus status, String operation, String id) {
        RuleApplyDocument resource = ruleEditService.findDto(id, true)
                .map(ruleApplyMapper::fromRuleDto)
                .orElseThrow(() -> new IllegalStateException("Applied rule not found: " + id));
        return ResponseEntity.status(status).body(new RuleApplyResult(operation, resource));
    }

    /**
     * responseId + responseBody indicates the expected content. If the content is the same, it will be used; if not, create a new response and rebind it at the same time.
     * Avoid modifying Response that is shared by other rules.
     */
    private void prepareApplyResponse(RuleDto dto) {
        if (dto.getResponseId() == null || dto.getResponseBody() == null) {
            return;
        }
        Response referenced = responseService.findById(dto.getResponseId())
                .orElseThrow(() -> new IllegalArgumentException("Response not found: " + dto.getResponseId()));
        boolean sameBody = Objects.equals(referenced.getBody(), dto.getResponseBody());
        boolean sameDescription = dto.getResponseDescription() == null
                || dto.getResponseDescription().isBlank()
                || Objects.equals(referenced.getDescription(), dto.getResponseDescription());
        if (sameBody && sameDescription) {
            dto.setResponseBody(null);
        } else {
            dto.setResponseId(null);
        }
    }
}
