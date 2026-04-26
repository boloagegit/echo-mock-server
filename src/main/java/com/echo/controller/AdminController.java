package com.echo.controller;

import com.echo.agent.AgentRegistry;
import com.echo.dto.AgentStatusDto;
import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.entity.ResponseContentType;
import com.echo.entity.RuleAuditLog;
import com.echo.protocol.ProtocolHandler;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.entity.BuiltinUser;
import com.echo.entity.Scenario;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.ResponseService;
import com.echo.service.RuleService;
import com.echo.service.RequestLogService;
import com.echo.service.RuleAuditService;
import com.echo.service.ExcelImportService;
import com.echo.service.OpenApiImportService;
import com.echo.service.H2BackupService;
import com.echo.service.CacheInvalidationService;
import com.echo.service.ContentTypeConstraints;
import com.echo.service.ResponseContentValidatorRegistry;
import com.echo.service.ScenarioService;
import com.echo.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 管理後台 API 控制器
 * <p>
 * 提供 Echo Mock Server 的管理功能：
 * <ul>
 *   <li>規則 CRUD（HTTP/JMS）</li>
 *   <li>共用回應管理</li>
 *   <li>請求日誌查詢</li>
 *   <li>稽核日誌查詢</li>
 *   <li>系統狀態與設定</li>
 *   <li>匯入/匯出功能</li>
 * </ul>
 * 
 * <h3>API 路徑</h3>
 * <ul>
 *   <li>GET /api/admin/status - 系統狀態</li>
 *   <li>GET/POST/PUT/DELETE /api/admin/rules - 規則管理</li>
 *   <li>GET/POST/PUT/DELETE /api/admin/responses - 回應管理</li>
 *   <li>GET /api/admin/logs - 請求日誌</li>
 *   <li>GET /api/admin/audit - 稽核日誌</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class AdminController {

    private final RuleService ruleService;
    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ResponseService responseService;
    private final RequestLogService requestLogService;
    private final Optional<RuleAuditService> ruleAuditService;
    private final Optional<com.echo.jms.JmsConnectionManager> jmsConnectionManager;
    private final Optional<H2BackupService> h2BackupService;
    private final ExcelImportService excelImportService;
    private final OpenApiImportService openApiImportService;
    private final Optional<CacheInvalidationService> cacheInvalidationService;
    private final ResponseContentValidatorRegistry responseContentValidatorRegistry;
    private final BuiltinUserRepository builtinUserRepository;
    private final AgentRegistry agentRegistry;
    private final CacheManager cacheManager;
    private final ScenarioService scenarioService;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @Value("${echo.ldap.enabled:false}")
    private boolean ldapEnabled;

    @Value("${echo.builtin-account.self-registration:false}")
    private boolean selfRegistrationEnabled;

    @Value("${echo.ldap.url:}")
    private String ldapUrl;

    @Value("${server.servlet.session.timeout:30m}")
    private String sessionTimeout;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${echo.jms.port:61616}")
    private int jmsPort;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${echo.http.alias:HTTP}")
    private String httpAlias;

    @Value("${echo.jms.alias:JMS}")
    private String jmsAlias;

    @Value("${echo.audit.retention-days:30}")
    private int auditRetentionDays;

    @Value("${echo.cleanup.rule-retention-days:180}")
    private int cleanupRetentionDays;

    @Value("${echo.env-label:}")
    private String envLabel;

    @Value("${echo.cleanup.response-retention-days:180}")
    private int responseRetentionDays;

    @Value("${echo.request-log.max-records:10000}")
    private int statsMaxRecords;

    private final Instant startupTime = Instant.now();

    // ========== 系統狀態 ==========

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @AuthenticationPrincipal UserDetails user) {
        Map<String, Object> status = new HashMap<>();
        status.put("serverPort", serverPort);
        status.put("sessionTimeout", sessionTimeout);
        status.put("datasourceUrl", datasourceUrl);
        status.put("jmsEnabled", protocolHandlerRegistry.isEnabled(Protocol.JMS));
        status.put("artemisBrokerUrl", "tcp://localhost:" + jmsPort);
        status.put("ldapEnabled", ldapEnabled);
        status.put("selfRegistrationEnabled", selfRegistrationEnabled);
        status.put("ldapUrl", ldapUrl);
        status.put("httpAlias", httpAlias);
        status.put("jmsAlias", jmsAlias);
        boolean loggedIn = user != null && !"anonymousUser".equals(user.getUsername());
        status.put("isLoggedIn", loggedIn);
        status.put("isAdmin", loggedIn && user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        status.put("username", loggedIn ? user.getUsername() : null);

        // 內建帳號資訊
        if (loggedIn) {
            Optional<BuiltinUser> builtinUser = builtinUserRepository.findByUsername(user.getUsername());
            if (builtinUser.isPresent()) {
                status.put("isBuiltinUser", true);
                status.put("forceChangePassword", Boolean.TRUE.equals(builtinUser.get().getForceChangePassword()));
            } else {
                status.put("isBuiltinUser", false);
                status.put("forceChangePassword", false);
            }
        } else {
            status.put("isBuiltinUser", false);
            status.put("forceChangePassword", false);
        }

        status.put("auditRetentionDays", auditRetentionDays);
        status.put("cleanupRetentionDays", cleanupRetentionDays);
        status.put("envLabel", envLabel);
        status.put("version", getClass().getPackage().getImplementationVersion() != null 
                ? getClass().getPackage().getImplementationVersion() : "dev");
        // 檢查孤兒規則
        status.put("orphanRules", responseService.countOrphanRules());

        // 檢查孤兒回應
        status.put("orphanResponses", responseService.countOrphanResponses());

        // 資料統計
        status.put("ruleCount", protocolHandlerRegistry.findAllRules().size());
        status.put("responseCount", responseService.count());
        status.put("requestLogCount", requestLogService.count());
        status.put("responseRetentionDays", responseRetentionDays);
        status.put("statsMaxRecords", statsMaxRecords);

        // DB 檔案大小
        try {
            Path dbFile = Paths.get("./mockdb.mv.db");
            if (Files.exists(dbFile)) {
                status.put("dbFileSize", Files.size(dbFile));
            }
        } catch (Exception e) {
            // ignore
        }

        // JVM 記憶體
        Runtime rt = Runtime.getRuntime();
        status.put("jvmHeapUsed", rt.totalMemory() - rt.freeMemory());
        status.put("jvmHeapMax", rt.maxMemory());

        // 啟動時間
        status.put("uptime", Duration.between(startupTime, Instant.now()).toSeconds());

        return ResponseEntity.ok(status);
    }

    // ========== 資料庫備份 ==========

    @Value("${echo.backup.enabled:false}")
    private boolean backupEnabled;

    // ========== Agent 狀態 ==========

    @GetMapping("/agents")
    public ResponseEntity<List<AgentStatusDto>> getAgentStatus() {
        List<AgentStatusDto> result = agentRegistry.getAll().stream()
                .map(a -> AgentStatusDto.builder()
                        .name(a.getName())
                        .description(a.getDescription())
                        .status(a.getStatus().name())
                        .queueSize(a.getStats().getQueueSize())
                        .processedCount(a.getStats().getProcessedCount())
                        .droppedCount(a.getStats().getDroppedCount())
                        .build())
                .toList();
        return ResponseEntity.ok(result);
    }

    // ========== 資料庫備份（續） ==========

    @Value("${echo.backup.cron:0 0 3 * * *}")
    private String backupCron;

    @Value("${echo.backup.path:./backups}")
    private String backupPath;

    @Value("${echo.backup.retention-days:7}")
    private int backupRetentionDays;

    @GetMapping("/backup/status")
    public ResponseEntity<Map<String, Object>> getBackupStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", backupEnabled);
        result.put("cron", backupCron);
        result.put("path", backupPath);
        result.put("retentionDays", backupRetentionDays);
        result.put("files", h2BackupService.map(H2BackupService::listBackups).orElse(List.of()));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/backup")
    public ResponseEntity<?> triggerBackup() {
        return h2BackupService
                .map(svc -> {
                    String filename = svc.backup("manual");
                    H2BackupService.CompactResult compact = svc.compact();
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("filename", filename);
                    if (compact != null) {
                        result.put("compactBefore", compact.sizeBefore());
                        result.put("compactAfter", compact.sizeAfter());
                    }
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.badRequest().body(Map.of("error", "Backup not enabled")));
    }

    // ========== 規則 CRUD ==========

    @GetMapping("/rules")
    public ResponseEntity<List<RuleDto>> listRules() {
        // 批次載入所有 Response
        Map<Long, Response> responseMap = responseService.findAll().stream()
                .collect(Collectors.toMap(Response::getId, r -> r));
        
        List<RuleDto> all = protocolHandlerRegistry.findAllRules().stream()
                .map(rule -> protocolHandlerRegistry.toDto(rule, responseMap.get(rule.getResponseId())))
                .toList();
        return ResponseEntity.ok(all);
    }

    @GetMapping("/rules/{id}")
    public ResponseEntity<RuleDto> getRule(@PathVariable String id) {
        return findRuleById(id, true)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/rules")
    public ResponseEntity<?> createRule(@RequestBody RuleDto dto) {
        validateProtocolEnabled(dto.getProtocol());
        validateScenarioFields(dto);
        dto.setId(null);
        SaveResult result = saveRule(dto);
        ruleAuditService.ifPresent(s -> s.logCreate(result.rule));
        return ResponseEntity.status(HttpStatus.CREATED).body(result.dto);
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<?> updateRule(@PathVariable String id, @RequestBody RuleDto dto) {
        validateProtocolEnabled(dto.getProtocol());
        validateScenarioFields(dto);
        return protocolHandlerRegistry.findById(id)
                .map(existing -> {
                    dto.setId(id);
                    dto.setVersion(existing.getVersion());
                    dto.setCreatedAt(existing.getCreatedAt()); // 保留建立時間
                    // 在 save 之前先序列化快照，避免 JPA merge 後 existing 被覆蓋
                    String beforeJson = ruleAuditService.map(s -> s.snapshot(existing)).orElse(null);
                    try {
                        SaveResult result = saveRule(dto);
                        ruleAuditService.ifPresent(s -> {
                            if (beforeJson != null) {
                                s.logUpdate(beforeJson, s.snapshot(result.rule));
                            } else {
                                s.logUpdate(existing, result.rule);
                            }
                        });
                        return ResponseEntity.ok(result.dto);
                    } catch (ObjectOptimisticLockingFailureException e) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of("error", "Rule was modified by another user. Please refresh and try again."));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable String id) {
        return protocolHandlerRegistry.findById(id)
                .map(existing -> {
                    Protocol protocol = existing.getProtocol();
                    protocolHandlerRegistry.getHandler(protocol)
                            .ifPresent(h -> h.deleteById(id));
                    ruleAuditService.ifPresent(s -> s.logDelete(existing));
                    evictCacheByProtocol(protocol);
                    cacheInvalidationService.ifPresent(s -> s.publishInvalidation(protocol));
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ========== 匯出/匯入 ==========

    @GetMapping("/rules/{id}/json")
    public ResponseEntity<RuleDto> exportRule(@PathVariable String id) {
        return findRuleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/rules/export")
    public ResponseEntity<List<RuleDto>> exportAllRules() {
        return ResponseEntity.ok(getAllRules());
    }

    @PostMapping("/rules/import")
    public ResponseEntity<?> importRule(@RequestBody RuleDto dto) {
        validateProtocolEnabled(dto.getProtocol());
        // 保留原有 ID（若有），讓 UUID 可跨環境同步
        dto.setVersion(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(saveRule(dto).dto);
    }

    @PostMapping("/rules/import-batch")
    public ResponseEntity<?> importRules(@RequestBody List<RuleDto> rules) {
        int imported = 0;
        for (RuleDto dto : rules) {
            if (!protocolHandlerRegistry.isEnabled(dto.getProtocol())) {
                continue;
            }
            // 保留原有 ID（若有），讓 UUID 可跨環境同步
            dto.setVersion(null);
            saveRule(dto);
            imported++;
        }
        return ResponseEntity.ok(Map.of("imported", imported));
    }

    @GetMapping(value = "/rules/import-template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        byte[] template = excelImportService.generateTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=echo-import-template.xlsx")
                .body(template);
    }

    @PostMapping("/rules/import-excel")
    public ResponseEntity<?> importExcel(@RequestParam("file") MultipartFile file) {
        try {
            List<Object> parsedRules = excelImportService.parseExcel(file);
            int imported = 0;
            for (Object rule : parsedRules) {
                if (rule instanceof BaseRule baseRule) {
                    if (!protocolHandlerRegistry.isEnabled(baseRule.getProtocol())) {
                        continue;
                    }
                    protocolHandlerRegistry.getHandler(baseRule.getProtocol())
                            .ifPresent(h -> h.save(baseRule));
                    imported++;
                }
            }
            if (imported > 0) {
                evictCacheByProtocol(Protocol.HTTP);
                evictCacheByProtocol(Protocol.JMS);
                cacheInvalidationService.ifPresent(CacheInvalidationService::publishInvalidation);
            }
            return ResponseEntity.ok(Map.of("imported", imported, "total", parsedRules.size()));
        } catch (Exception e) {
            log.error("Excel import failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Excel 解析失敗: " + e.getMessage()));
        }
    }

    // ========== OpenAPI Import ==========

    @PostMapping(value = "/rules/import-openapi/preview", consumes = "multipart/form-data")
    public ResponseEntity<?> previewOpenApiImport(@RequestParam("file") MultipartFile file) {
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
            SaveResult result = saveRule(dto);
            ruleAuditService.ifPresent(s -> s.logCreate(result.rule));
            imported++;
        }
        return ResponseEntity.ok(Map.of("imported", imported));
    }

    // ========== Response API ==========

    @GetMapping("/responses")
    public ResponseEntity<List<Response>> listResponses(@RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(responseService.search(keyword));
    }

    @GetMapping("/responses/summary")
    public ResponseEntity<List<ResponseService.ResponseSummary>> listResponseSummary() {
        return ResponseEntity.ok(responseService.findAllWithUsage());
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
    public ResponseEntity<Response> updateResponse(@PathVariable Long id, @RequestBody Response response) {
        return responseService.findById(id)
                .map(existing -> {
                    Response before = Response.builder().id(existing.getId()).description(existing.getDescription()).body(existing.getBody()).build();
                    response.setId(id);
                    response.setVersion(existing.getVersion());
                    Response saved = responseService.save(response);
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

    // ========== 批次刪除 ==========

    @DeleteMapping("/rules/batch")
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true)
    public ResponseEntity<?> deleteRules(@RequestBody List<String> ids) {
        int deleted = 0;
        for (String id : ids) {
            var rule = findRuleById(id);
            if (rule.isPresent()) {
                protocolHandlerRegistry.getHandler(rule.get().getProtocol())
                        .ifPresent(h -> h.deleteById(id));
                deleted++;
            }
        }
        if (deleted > 0) {
            cacheInvalidationService.ifPresent(CacheInvalidationService::publishInvalidation);
        }
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @DeleteMapping("/rules/all")
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true)
    public ResponseEntity<?> deleteAllRules() {
        int count = protocolHandlerRegistry.getAllHandlers().stream()
                .mapToInt(h -> h.deleteAll())
                .sum();
        if (count > 0) {
            cacheInvalidationService.ifPresent(CacheInvalidationService::publishInvalidation);
        }
        return ResponseEntity.ok(Map.of("deleted", count));
    }

    @GetMapping("/responses/export")
    public ResponseEntity<List<Response>> exportAllResponses() {
        return ResponseEntity.ok(responseService.findAll());
    }

    @PostMapping("/responses/import-batch")
    public ResponseEntity<?> importResponses(@RequestBody List<Response> responses) {
        int count = 0;
        for (Response r : responses) {
            r.setId(null);
            r.setVersion(null);
            responseService.save(r);
            count++;
        }
        return ResponseEntity.ok(Map.of("imported", count));
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

    // ========== 請求記錄 ==========

    @GetMapping("/logs")
    public ResponseEntity<RequestLogService.SummaryQueryResult> queryLogs(
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) Boolean matched,
            @RequestParam(required = false) String endpoint) {
        RequestLogService.QueryFilter filter = RequestLogService.QueryFilter.builder()
                .ruleId(ruleId)
                .protocol(protocol != null ? Protocol.valueOf(protocol) : null)
                .matched(matched)
                .endpoint(endpoint)
                .build();
        return ResponseEntity.ok(requestLogService.querySummary(filter));
    }

    @GetMapping("/logs/{id}/detail")
    public ResponseEntity<RequestLogService.LogEntry> getLogDetail(@PathVariable Long id) {
        return requestLogService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/logs/summary")
    public ResponseEntity<RequestLogService.Summary> getLogSummary() {
        return ResponseEntity.ok(requestLogService.getSummary());
    }

    @DeleteMapping("/logs/all")
    public ResponseEntity<Map<String, Object>> deleteAllLogs() {
        long deleted = requestLogService.deleteAll();
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    @GetMapping("/logs/{id}/to-rule")
    public ResponseEntity<RuleDto> logToRule(@PathVariable Long id) {
        return requestLogService.findById(id)
                .map(entry -> {
                    RuleDto.RuleDtoBuilder builder = RuleDto.builder()
                            .protocol(entry.getProtocol())
                            .matchKey(entry.getEndpoint())
                            .enabled(true)
                            .priority(0)
                            .delayMs(0L)
                            .faultType("NONE");

                    if (entry.getProtocol() == Protocol.HTTP) {
                        builder.method(entry.getMethod())
                               .targetHost(entry.getTargetHost())
                               .status(entry.getResponseStatus() != null ? entry.getResponseStatus() : 200);
                    }

                    if (entry.getResponseBody() != null && !entry.getResponseBody().isBlank()) {
                        builder.responseBody(entry.getResponseBody());
                    }

                    String desc = "[From Log] ";
                    if (entry.getMethod() != null) {
                        desc += entry.getMethod() + " ";
                    }
                    desc += entry.getEndpoint();
                    builder.description(desc);

                    return ResponseEntity.ok(builder.build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ========== 審計記錄 ==========

    @GetMapping("/rules/{id}/audit")
    public ResponseEntity<List<RuleAuditLog>> getRuleAuditLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limit) {
        return ruleAuditService
                .map(s -> ResponseEntity.ok(s.getAuditLogs(id, limit)))
                .orElse(ResponseEntity.ok(List.of()));
    }

    @GetMapping("/audit")
    public ResponseEntity<List<RuleAuditLog>> getAllAuditLogs(
            @RequestParam(defaultValue = "1000") int limit) {
        return ruleAuditService
                .map(s -> ResponseEntity.ok(s.getAllAuditLogs(limit)))
                .orElse(ResponseEntity.ok(List.of()));
    }

    @DeleteMapping("/audit/all")
    public ResponseEntity<Map<String, Object>> deleteAllAuditLogs() {
        long deleted = ruleAuditService.map(s -> s.deleteAll()).orElse(0L);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    // ========== 規則啟用/停用 ==========

    @PutMapping("/rules/{id}/enable")
    public ResponseEntity<?> enableRule(@PathVariable String id) {
        return findRuleById(id)
                .map(dto -> {
                    ruleService.updateEnabled(List.of(id), true);
                    dto.setEnabled(true);
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/rules/{id}/disable")
    public ResponseEntity<?> disableRule(@PathVariable String id) {
        return findRuleById(id)
                .map(dto -> {
                    ruleService.updateEnabled(List.of(id), false);
                    dto.setEnabled(false);
                    return ResponseEntity.ok(dto);
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

    @PutMapping("/rules/{id}/protect")
    public ResponseEntity<?> protectRule(@PathVariable String id) {
        return ruleService.updateProtected(List.of(id), true) > 0
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @PutMapping("/rules/{id}/unprotect")
    public ResponseEntity<?> unprotectRule(@PathVariable String id) {
        return ruleService.updateProtected(List.of(id), false) > 0
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
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

    // ========== Scenario 管理 ==========

    @GetMapping("/scenarios")
    public ResponseEntity<List<Scenario>> listScenarios() {
        return ResponseEntity.ok(scenarioService.findAll());
    }

    @PutMapping("/scenarios/{name}/reset")
    public ResponseEntity<?> resetScenario(@PathVariable String name) {
        scenarioService.resetScenario(name);
        return ResponseEntity.ok(Map.of("success", true, "scenarioName", name));
    }

    @PutMapping("/scenarios/reset")
    public ResponseEntity<?> resetAllScenarios() {
        scenarioService.resetAll();
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ========== JMS 測試 ==========
    @PostMapping("/jms/test")
    public ResponseEntity<?> testJms(@RequestBody String message) {
        if (!protocolHandlerRegistry.isEnabled(Protocol.JMS) || jmsConnectionManager.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "JMS not enabled"));
        }
        var jms = jmsConnectionManager.get();
        if (jms.getJmsTemplate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "JMS not connected"));
        }
        String queue = jms.getStatus().getQueue();
        jms.getJmsTemplate().convertAndSend(queue, message);
        return ResponseEntity.ok(Map.of("sent", true, "queue", queue));
    }

    // ========== Helper Methods ==========

    private record SaveResult(BaseRule rule, RuleDto dto) {}

    /** 根據協定清除對應的規則快取 */
    private void evictCacheByProtocol(Protocol protocol) {
        String cacheName = (protocol == Protocol.JMS)
                ? CacheConfig.JMS_RULES_CACHE
                : CacheConfig.HTTP_RULES_CACHE;
        var cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    private void validateProtocolEnabled(Protocol protocol) {
        if (!protocolHandlerRegistry.isEnabled(protocol)) {
            throw new IllegalArgumentException(protocol + " is not enabled");
        }
    }

    private void validateScenarioFields(RuleDto dto) {
        boolean hasRequired = dto.getRequiredScenarioState() != null && !dto.getRequiredScenarioState().isBlank();
        boolean hasNew = dto.getNewScenarioState() != null && !dto.getNewScenarioState().isBlank();
        boolean hasName = dto.getScenarioName() != null && !dto.getScenarioName().isBlank();
        if ((hasRequired || hasNew) && !hasName) {
            throw new IllegalArgumentException(
                    "scenarioName is required when requiredScenarioState or newScenarioState is set");
        }
    }

    private List<RuleDto> getAllRules() {
        return protocolHandlerRegistry.findAllRules().stream()
                .map(r -> protocolHandlerRegistry.toDto(r, getResponse(r.getResponseId())))
                .toList();
    }

    private Optional<RuleDto> findRuleById(String id) {
        return findRuleById(id, false);
    }

    private Optional<RuleDto> findRuleById(String id, boolean includeBody) {
        return protocolHandlerRegistry.findById(id)
                .map(r -> protocolHandlerRegistry.toDto(r, getResponse(r.getResponseId()), includeBody));
    }

    private SaveResult saveRule(RuleDto dto) {
        Response response;
        // 使用現有 Response
        if (dto.getResponseId() != null && (dto.getResponseBody() == null || dto.getResponseBody().isBlank())) {
            response = responseService.findById(dto.getResponseId())
                    .orElseThrow(() -> new IllegalArgumentException("Response not found: " + dto.getResponseId()));
        } else {
            // 驗證回應內容格式
            if (dto.getResponseBody() != null && !dto.getResponseBody().isBlank()) {
                ResponseContentType contentType = ContentTypeConstraints.infer(dto.getProtocol(), dto.getSseEnabled());
                responseContentValidatorRegistry.getValidator(contentType).validate(dto.getResponseBody());
            }
            // 建立新 Response - 自動產生描述
            String desc = dto.getResponseDescription();
            if (desc == null || desc.isBlank()) {
                desc = protocolHandlerRegistry.generateDescription(dto);
            }
            response = responseService.save(Response.builder()
                    .description(desc)
                    .body(dto.getResponseBody())
                    .build());
            dto.setResponseId(response.getId());
        }
        dto.setResponseBody(null);
        
        ProtocolHandler handler = protocolHandlerRegistry.getHandler(dto.getProtocol())
                .orElseThrow(() -> new IllegalArgumentException("Unknown protocol: " + dto.getProtocol()));
        BaseRule saved = handler.save(handler.fromDto(dto));
        evictCacheByProtocol(dto.getProtocol());
        cacheInvalidationService.ifPresent(s -> s.publishInvalidation(dto.getProtocol()));
        return new SaveResult(saved, handler.toDto(saved, response, false));
    }

    private Response getResponse(Long responseId) {
        return responseId != null ? responseService.findById(responseId).orElse(null) : null;
    }

    // ========== 規則測試 ==========

    @PostMapping("/rules/{id}/test")
    public ResponseEntity<?> testRule(@PathVariable String id, @RequestBody Map<String, Object> testRequest) {
        return protocolHandlerRegistry.findById(id)
                .map(rule -> {
                    testRequest.put("serverPort", serverPort);
                    Map<String, Object> result = protocolHandlerRegistry.getHandler(rule.getProtocol())
                            .map(h -> h.testRule(rule, testRequest))
                            .orElse(Map.of("status", 400, "body", "Unknown protocol", "elapsed", 0));
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
