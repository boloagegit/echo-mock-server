package com.echo.controller;

import com.echo.dto.RuleDto;
import com.echo.dto.RuleImportRequest;
import com.echo.dto.RuleImportPreview;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.service.ExcelImportService;
import com.echo.service.OpenApiImportService;
import com.echo.service.ResponseService;
import com.echo.service.RuleEditService;
import com.echo.service.RuleImportService;
import com.echo.service.RuleImportValidationException;
import com.echo.service.RuleQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final ExcelImportService excelImportService;
    private final OpenApiImportService openApiImportService;
    private final RuleImportService ruleImportService;
    private final RuleQueryService ruleQueryService;

    @Value("${echo.features.bulk-import-export-enabled:false}")
    private boolean bulkImportExportEnabled;

    private static <T> ResponseEntity<T> featureUnavailable() {
        return ResponseEntity.notFound().build();
    }

    // ========== Rules ==========

    /** Exports every rule, or only the rules matching the same filters as {@code /rules/page}. */
    @GetMapping("/rules/export")
    public ResponseEntity<List<RuleDto>> exportAllRules(
            @RequestParam(required = false) Protocol protocol,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean isProtected,
            @RequestParam(required = false) RuleQueryService.RuleMode mode,
            @RequestParam(required = false) Boolean expiring,
            @RequestParam(required = false) Boolean unreachable,
            @RequestParam(required = false) String keyword) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        boolean filtered = protocol != null || enabled != null || isProtected != null || mode != null
                || expiring != null || unreachable != null || (keyword != null && !keyword.isBlank());
        if (!filtered) {
            return ResponseEntity.ok(ruleEditService.findAllDtos());
        }
        List<String> ids = ruleQueryService.findIds(new RuleQueryService.RuleQuery(
                protocol, enabled, isProtected, keyword, 0, 1, null, null, mode, expiring, unreachable));
        return ResponseEntity.ok(ruleEditService.findExportDtos(ids));
    }

    /** IDs of the rules to export; null is treated as an empty selection. */
    public record RuleExportRequest(List<String> ids) {
        public RuleExportRequest {
            ids = ids == null ? List.of() : List.copyOf(ids.stream().filter(java.util.Objects::nonNull).toList());
        }
    }

    /** Exports the selected rules; unknown IDs are ignored. */
    @PostMapping("/rules/export")
    public ResponseEntity<List<RuleDto>> exportSelectedRules(@RequestBody RuleExportRequest request) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        List<String> ids = request == null || request.ids() == null ? List.of() : request.ids();
        return ResponseEntity.ok(ruleEditService.findExportDtos(ids));
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

    /** Parses an Excel sheet without writing; the client reviews it through the JSON preview. */
    @PostMapping(value = "/rules/import-excel/parse", consumes = "multipart/form-data")
    public ResponseEntity<?> parseExcel(@RequestParam("file") MultipartFile file) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        try {
            return ResponseEntity.ok(Map.of("rules", excelImportService.parseRules(file)));
        } catch (Exception e) {
            log.warn("Excel parse failed: {}", e.getMessage());
            return excelParseFailed(e);
        }
    }

    /** One-step Excel import kept for API clients; like JSON, the whole sheet commits or none of it. */
    @PostMapping("/rules/import-excel")
    public ResponseEntity<?> importExcel(@RequestParam("file") MultipartFile file) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        List<RuleDto> parsedRules;
        try {
            parsedRules = excelImportService.parseRules(file);
        } catch (Exception e) {
            log.warn("Excel import failed: {}", e.getMessage());
            return excelParseFailed(e);
        }
        // Rows for a disabled protocol were always skipped by this endpoint.
        List<RuleDto> importable = parsedRules.stream()
                .filter(rule -> rule.getProtocol() != null && protocolHandlerRegistry.isEnabled(rule.getProtocol()))
                .toList();
        int imported = importable.isEmpty() ? 0
                : ruleImportService.importRules(new RuleImportRequest(importable, Map.of())).imported();
        return ResponseEntity.ok(Map.of("imported", imported, "total", parsedRules.size()));
    }

    private static ResponseEntity<?> excelParseFailed(Exception e) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "EXCEL_PARSE_FAILED",
                "error", "Excel parse failed: " + e.getMessage()));
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
            result.getRules().forEach(rule -> {
                if (rule.getProtocol() == null) {
                    rule.setProtocol(Protocol.HTTP);
                }
            });
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

    /** Imports reviewed OpenAPI rules atomically; rules for a disabled protocol are skipped. */
    @PostMapping("/rules/import-openapi/confirm")
    public ResponseEntity<?> confirmOpenApiImport(@RequestBody List<RuleDto> rules) {
        if (!bulkImportExportEnabled) {
            return featureUnavailable();
        }
        rules.forEach(dto -> {
            if (dto.getProtocol() == null) {
                dto.setProtocol(Protocol.HTTP);
            }
        });
        List<RuleDto> importable = rules.stream()
                .filter(dto -> protocolHandlerRegistry.isEnabled(dto.getProtocol()))
                .toList();
        int imported = importable.isEmpty() ? 0
                : ruleImportService.importRules(new RuleImportRequest(importable, Map.of())).imported();
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
