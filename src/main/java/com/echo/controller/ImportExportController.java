package com.echo.controller;

import com.echo.dto.RuleDto;
import com.echo.dto.RuleImportRequest;
import com.echo.dto.RuleImportPreview;
import com.echo.entity.BaseRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.service.ExcelImportService;
import com.echo.service.OpenApiImportService;
import com.echo.service.ResponseService;
import com.echo.service.RuleAuditService;
import com.echo.service.RuleEditService;
import com.echo.service.RuleImportService;
import com.echo.service.RuleImportValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Batch import/export of rules and responses (JSON, Excel, OpenAPI).
 * {@code echo.features.bulk-import-export-enabled} needs to be turned on; when turned off, these API will all return 404.
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class ImportExportController {

    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final RuleEditService ruleEditService;
    private final ResponseService responseService;
    private final Optional<RuleAuditService> ruleAuditService;
    private final ExcelImportService excelImportService;
    private final OpenApiImportService openApiImportService;
    private final RuleImportService ruleImportService;

    @Value("${echo.features.bulk-import-export-enabled:false}")
    private boolean bulkImportExportEnabled;

    private static <T> ResponseEntity<T> featureUnavailable() {
        return ResponseEntity.notFound().build();
    }

    // ========== Rules ==========

    @GetMapping("/rules/export")
    public ResponseEntity<List<RuleDto>> exportAllRules() {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        return ResponseEntity.ok(ruleEditService.findAllDtos());
    }

    @PostMapping("/rules/import")
    public ResponseEntity<?> importRule(@RequestBody RuleDto dto) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        RuleImportService.Result imported = ruleImportService.importRules(new RuleImportRequest(List.of(dto), Map.of()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleEditService.findDto(imported.rules().get(0).id(), true).orElseThrow());
    }

    @PostMapping("/rules/import-batch")
    public ResponseEntity<?> importRules(@RequestBody List<RuleDto> rules) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        return ResponseEntity.ok(ruleImportService.importRules(new RuleImportRequest(rules, Map.of())));
    }

    @PostMapping("/rules/import/preview")
    public ResponseEntity<RuleImportPreview> previewRules(@RequestBody RuleImportRequest request) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        return ResponseEntity.ok(ruleImportService.preview(request));
    }

    @PostMapping("/rules/import/confirm")
    public ResponseEntity<?> confirmRules(@RequestBody RuleImportRequest request) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        return ResponseEntity.ok(ruleImportService.importRules(request));
    }

    @ExceptionHandler(RuleImportValidationException.class)
    public ResponseEntity<?> importInvalid(RuleImportValidationException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage(), "errors", error.errors()));
    }

    @GetMapping(value = "/rules/import-template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        byte[] template = excelImportService.generateTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=echo-import-template.xlsx")
                .body(template);
    }

    @PostMapping("/rules/import-excel")
    public ResponseEntity<?> importExcel(@RequestParam("file") MultipartFile file) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        try {
            List<Object> parsedRules = excelImportService.parseExcel(file);
            int imported = 0;
            for (Object rule : parsedRules) {
                if (rule instanceof BaseRule baseRule) {
                    if (!protocolHandlerRegistry.isEnabled(baseRule.getProtocol())) {
                        continue;
                    }
                    ruleEditService.stampForSave(baseRule);
                    protocolHandlerRegistry.getHandler(baseRule.getProtocol())
                            .ifPresent(h -> h.save(baseRule));
                    imported++;
                }
            }
            if (imported > 0) {
                ruleEditService.allRulesChanged();
            }
            return ResponseEntity.ok(Map.of("imported", imported, "total", parsedRules.size()));
        } catch (Exception e) {
            log.error("Excel import failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Excel 解析失敗: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/rules/import-openapi/preview", consumes = "multipart/form-data")
    public ResponseEntity<?> previewOpenApiImport(@RequestParam("file") MultipartFile file) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            OpenApiImportService.OpenApiParseResult result = openApiImportService.parse(content);
            if (!result.isSuccess()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "errors", result.getErrors()));
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "title", result.getTitle() != null ? result.getTitle() : "",
                    "version", result.getVersion() != null ? result.getVersion() : "",
                    "rules", result.getRules(),
                    "errors", result.getErrors()));
        } catch (Exception e) {
            log.error("OpenAPI preview failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "errors", List.of("Failed to parse OpenAPI spec: " + e.getMessage())));
        }
    }

    @PostMapping("/rules/import-openapi/confirm")
    @Transactional
    public ResponseEntity<?> confirmOpenApiImport(@RequestBody List<RuleDto> rules) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        int imported = 0;
        for (RuleDto dto : rules) {
            if (dto.getProtocol() == null) {
                dto.setProtocol(Protocol.HTTP);
            }
            if (!protocolHandlerRegistry.isEnabled(dto.getProtocol())) {
                continue;
            }
            dto.setId(null);
            dto.setVersion(null);
            RuleEditService.Saved result = ruleEditService.save(dto);
            ruleAuditService.ifPresent(s -> s.logCreate(result.rule()));
            imported++;
        }
        return ResponseEntity.ok(Map.of("imported", imported));
    }

    // ========== Response ==========

    @GetMapping("/responses/export")
    public ResponseEntity<List<Response>> exportAllResponses() {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        return ResponseEntity.ok(responseService.findAll());
    }

    @PostMapping("/responses/import-batch")
    public ResponseEntity<?> importResponses(@RequestBody List<Response> responses) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        int count = 0;
        for (Response r : responses) {
            r.setId(null);
            r.setVersion(null);
            responseService.save(r);
            count++;
        }
        return ResponseEntity.ok(Map.of("imported", count));
    }
}
