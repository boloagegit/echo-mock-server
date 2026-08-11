package com.echo.controller;

import com.echo.agent.AgentRegistry;
import com.echo.dto.AgentStatusDto;
import com.echo.dto.RuleApplyDocument;
import com.echo.dto.RuleApplyResult;
import com.echo.dto.RuleApplySchema;
import com.echo.dto.RuleDto;
import com.echo.dto.RulePageDto;
import com.echo.dto.RuleGroupSummaryDto;
import com.echo.entity.BaseRule;
import com.echo.entity.FaultType;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.entity.ResponseContentType;
import com.echo.entity.RuleAuditLog;
import com.echo.entity.HttpRuleAction;
import com.echo.protocol.ProtocolHandler;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.ResponseService;
import com.echo.service.RuleApplyMapper;
import com.echo.service.RuleApplyContractService;
import com.echo.service.RuleApplyPersistenceSynchronizer;
import com.echo.service.RuleApplyValidationException;
import com.echo.service.RuleService;
import com.echo.service.RuleQueryService;
import com.echo.service.RequestLogService;
import com.echo.service.RuleAuditService;
import com.echo.service.ExcelImportService;
import com.echo.service.BackupService;
import com.echo.service.CacheInvalidationService;
import com.echo.service.ContentTypeConstraints;
import com.echo.service.IssueReportService;
import com.echo.service.HttpTargetConnectionService;
import com.echo.service.OpenApiImportService;
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
import java.util.Objects;
import java.util.Optional;

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
    private final RuleQueryService ruleQueryService;
    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ResponseService responseService;
    private final RequestLogService requestLogService;
    private final Optional<RuleAuditService> ruleAuditService;
    private final Optional<com.echo.jms.JmsConnectionManager> jmsConnectionManager;
    private final Optional<BackupService> backupService;
    private final ExcelImportService excelImportService;
    private final OpenApiImportService openApiImportService;
    private final Optional<CacheInvalidationService> cacheInvalidationService;
    private final ResponseContentValidatorRegistry responseContentValidatorRegistry;
    private final BuiltinUserRepository builtinUserRepository;
    private final AgentRegistry agentRegistry;
    private final CacheManager cacheManager;
    private final IssueReportService issueReportService;
    private final Optional<HttpTargetConnectionService> httpTargetConnectionService;
    private final RuleApplyMapper ruleApplyMapper;
    private final RuleApplyPersistenceSynchronizer ruleApplyPersistenceSynchronizer;
    private final RuleApplyContractService ruleApplyContractService;
    private final ScenarioService scenarioService;

    @ExceptionHandler(RuleApplyValidationException.class)
    public ResponseEntity<Map<String, Object>> handleRuleApplyValidation(RuleApplyValidationException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.getMessage());
        body.put("validationCode", e.getValidationCode());
        body.put("path", e.getPath());
        body.putAll(e.getDetails());
        return ResponseEntity.badRequest().body(body);
    }

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

    @Value("${echo.features.bulk-import-export-enabled:false}")
    private boolean bulkImportExportEnabled;

    @Value("${echo.features.scenarios-enabled:false}")
    private boolean scenariosEnabled;

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
        status.put("bulkImportExportEnabled", bulkImportExportEnabled);
        status.put("scenariosEnabled", scenariosEnabled);
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
        status.put("ruleCount", protocolHandlerRegistry.getAllHandlers().stream()
                .mapToLong(com.echo.protocol.ProtocolHandler::count)
                .sum());
        status.put("responseCount", responseService.count());
        status.put("requestLogCount", requestLogService.count());
        status.put("responseRetentionDays", responseRetentionDays);
        status.put("statsMaxRecords", statsMaxRecords);

        // DB 檔案大小
        try {
            databaseFilePath(datasourceUrl)
                    .filter(Files::exists)
                    .ifPresent(path -> putDatabaseFileSize(status, path));
        } catch (Exception e) {
            // ignore
        }

        // JVM 記憶體
        Runtime rt = Runtime.getRuntime();
        status.put("jvmHeapUsed", rt.totalMemory() - rt.freeMemory());
        status.put("jvmHeapMax", rt.maxMemory());
        status.put("ruleCaches", getRuleCacheStats());

        // 啟動時間
        status.put("uptime", Duration.between(startupTime, Instant.now()).toSeconds());

        // Issue Report
        status.put("openIssueCount", issueReportService.countOpen());

        return ResponseEntity.ok(status);
    }

    private Map<String, Object> getRuleCacheStats() {
        Map<String, Object> result = new HashMap<>();
        for (String cacheName : CacheConfig.ALL_RULE_CACHES) {
            org.springframework.cache.Cache springCache = cacheManager.getCache(cacheName);
            if (springCache == null
                    || !(springCache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> cache)) {
                continue;
            }
            var stats = cache.stats();
            result.put(cacheName, Map.of(
                    "entries", cache.estimatedSize(),
                    "requestCount", stats.requestCount(),
                    "hitRate", stats.requestCount() == 0 ? 0.0 : stats.hitRate(),
                    "evictionCount", stats.evictionCount()));
        }
        return result;
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
        result.put("files", backupService.map(BackupService::listBackups).orElse(List.of()));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/backup")
    public ResponseEntity<?> triggerBackup() {
        return backupService
                .map(svc -> {
                    String filename = svc.backup("manual");
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("filename", filename);
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.badRequest().body(Map.of("error", "Backup not enabled")));
    }

    // ========== 規則 CRUD ==========

    @GetMapping("/rules")
    public ResponseEntity<List<RuleDto>> listRules() {
        // 列表只需要規則 metadata。不傳入 Response，避免讀取整張 responses 的 LOB body。
        List<RuleDto> all = protocolHandlerRegistry.findAllRules().stream()
                .map(rule -> protocolHandlerRegistry.toDto(rule, null))
                .toList();
        return ResponseEntity.ok(all);
    }

    @GetMapping("/rules/page")
    public ResponseEntity<RulePageDto> queryRules(
            @RequestParam(required = false) Protocol protocol,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean isProtected,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false, name = "sort") String sortField,
            @RequestParam(required = false) String direction) {
        var result = ruleQueryService.query(new RuleQueryService.RuleQuery(
                protocol, enabled, isProtected, keyword, page, size, sortField, direction));
        List<RuleDto> rules = result.rules().stream()
                .map(rule -> protocolHandlerRegistry.toDto(rule, null))
                .toList();
        return ResponseEntity.ok(new RulePageDto(rules, result.page(), result.size(),
                result.totalElements(), result.totalPages()));
    }

    @GetMapping("/rules/groups")
    public ResponseEntity<RuleGroupSummaryDto> queryRuleGroups(
            @RequestParam(required = false) Protocol protocol,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean isProtected,
            @RequestParam(required = false) String keyword) {
        var result = ruleQueryService.queryGroupSummary(new RuleQueryService.RuleQuery(
                protocol, enabled, isProtected, keyword, 0, 20, null, null));
        return ResponseEntity.ok(new RuleGroupSummaryDto(
                result.tagKeys(), result.counts(), result.totalElements()));
    }

    @GetMapping("/rules/group")
    public ResponseEntity<RulePageDto> queryRuleGroup(
            @RequestParam String key,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) Protocol protocol,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean isProtected,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false, name = "sort") String sortField,
            @RequestParam(required = false) String direction) {
        int effectiveLimit = Math.max(1, limit);
        var result = ruleQueryService.queryGroup(new RuleQueryService.RuleQuery(
                protocol, enabled, isProtected, keyword, 0, effectiveLimit, sortField, direction),
                key, value, effectiveLimit);
        List<RuleDto> rules = result.rules().stream()
                .map(rule -> protocolHandlerRegistry.toDto(rule, null))
                .toList();
        int totalPages = result.totalElements() == 0 ? 0
                : (int) Math.ceil((double) result.totalElements() / effectiveLimit);
        return ResponseEntity.ok(new RulePageDto(
                rules, 0, effectiveLimit, result.totalElements(), totalPages));
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
                                .body(Map.of("error", "OPTIMISTIC_LOCK_CONFLICT"));
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

    @GetMapping("/rules/{id}/manifest")
    public ResponseEntity<RuleApplyDocument> getRuleManifest(@PathVariable String id) {
        return findRuleById(id, true)
                .map(ruleApplyMapper::fromRuleDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Returns the machine-readable contract used by the declarative editor and server validator. */
    @GetMapping("/rules/schema")
    public RuleApplySchema getRuleApplySchema() {
        return ruleApplyContractService.schema();
    }

    /**
     * 以宣告式 JSON 文件建立或更新規則。
     * metadata.id 不存在時建立；存在時以 resourceVersion 做樂觀鎖更新。
     */
    @PostMapping("/rules/apply")
    @Transactional
    public ResponseEntity<?> applyRule(@RequestBody RuleApplyDocument document) {
        ruleApplyContractService.validate(document);
        RuleDto dto = ruleApplyMapper.toRuleDto(document);
        validateProtocolEnabled(dto.getProtocol());

        RuleApplyDocument.Metadata metadata = document.getMetadata();
        String id = metadata == null || metadata.getId() == null || metadata.getId().isBlank()
                ? null : metadata.getId().trim();
        Long requestedVersion = metadata == null ? null : metadata.getResourceVersion();
        Optional<? extends BaseRule> existing = id == null
                ? Optional.empty()
                : protocolHandlerRegistry.findById(id);

        if (existing.isPresent()) {
            BaseRule current = existing.get();
            if (requestedVersion == null) {
                throw new IllegalArgumentException("metadata.resourceVersion is required when updating an existing rule");
            }
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
                SaveResult saved = saveRule(dto);
                ruleAuditService.ifPresent(service -> {
                    if (beforeJson != null) {
                        service.logUpdate(beforeJson, service.snapshot(saved.rule));
                    } else {
                        service.logUpdate(current, saved.rule);
                    }
                });
                ruleApplyPersistenceSynchronizer.flush();
                return applyResult(HttpStatus.OK, "UPDATED", saved.rule.getId());
            } catch (ObjectOptimisticLockingFailureException e) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "OPTIMISTIC_LOCK_CONFLICT"));
            }
        }

        if (requestedVersion != null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "RESOURCE_NOT_FOUND"));
        }

        dto.setId(id);
        dto.setVersion(null);
        prepareApplyResponse(dto);
        SaveResult saved = saveRule(dto);
        ruleAuditService.ifPresent(service -> service.logCreate(saved.rule));
        ruleApplyPersistenceSynchronizer.flush();
        return applyResult(HttpStatus.CREATED, "CREATED", saved.rule.getId());
    }

    @GetMapping("/rules/export")
    public ResponseEntity<List<RuleDto>> exportAllRules() {
        if (!bulkImportExportEnabled) return featureUnavailable();
        return ResponseEntity.ok(getAllRules());
    }

    @PostMapping("/rules/import")
    public ResponseEntity<?> importRule(@RequestBody RuleDto dto) {
        if (!bulkImportExportEnabled) return featureUnavailable();
        validateProtocolEnabled(dto.getProtocol());
        // 保留原有 ID（若有），讓 UUID 可跨環境同步
        dto.setVersion(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(saveRule(dto).dto);
    }

    @PostMapping("/rules/import-batch")
    public ResponseEntity<?> importRules(@RequestBody List<RuleDto> rules) {
        if (!bulkImportExportEnabled) return featureUnavailable();
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
        if (!bulkImportExportEnabled) return featureUnavailable();
        byte[] template = excelImportService.generateTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=echo-import-template.xlsx")
                .body(template);
    }

    @PostMapping("/rules/import-excel")
    public ResponseEntity<?> importExcel(@RequestParam("file") MultipartFile file) {
        if (!bulkImportExportEnabled) return featureUnavailable();
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
        if (!bulkImportExportEnabled) return featureUnavailable();
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
        if (!bulkImportExportEnabled) return featureUnavailable();
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
        if (!bulkImportExportEnabled) return featureUnavailable();
        return ResponseEntity.ok(responseService.findAll());
    }

    @PostMapping("/responses/import-batch")
    public ResponseEntity<?> importResponses(@RequestBody List<Response> responses) {
        if (!bulkImportExportEnabled) return featureUnavailable();
        int count = 0;
        for (Response r : responses) {
            r.setId(null);
            r.setVersion(null);
            responseService.save(r);
            count++;
        }
        return ResponseEntity.ok(Map.of("imported", count));
    }

    private <T> ResponseEntity<T> featureUnavailable() {
        return ResponseEntity.notFound().build();
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
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, name = "sort") String sortField,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) Long afterId) {
        RequestLogService.QueryFilter filter = RequestLogService.QueryFilter.builder()
                .ruleId(ruleId)
                .protocol(protocol != null ? Protocol.valueOf(protocol) : null)
                .matched(matched)
                .endpoint(endpoint)
                .page(page)
                .size(size != null ? size : limit)
                .sortField(sortField)
                .sortDirection(direction)
                .afterId(afterId)
                .build();
        return ResponseEntity.ok(requestLogService.querySummary(filter));
    }

    /** 保留給既有 Java 呼叫端與單元測試的相容入口。 */
    public ResponseEntity<RequestLogService.SummaryQueryResult> queryLogs(
            String ruleId, String protocol, Boolean matched, String endpoint) {
        return queryLogs(ruleId, protocol, matched, endpoint,
                null, null, null, null, null, null);
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
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false, name = "sort") String sortField,
            @RequestParam(required = false) String direction) {
        RuleAuditService.AuditQueryFilter filter = RuleAuditService.AuditQueryFilter.builder()
                .action(action == null || action.isBlank() ? null : RuleAuditLog.Action.valueOf(action))
                .operator(operator)
                .keyword(keyword)
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
    public ResponseEntity<?> listScenarios() {
        if (!scenariosEnabled) return featureUnavailable();
        return ResponseEntity.ok(scenarioService.findAll());
    }

    @PutMapping("/scenarios/{name}/reset")
    public ResponseEntity<?> resetScenario(@PathVariable String name) {
        if (!scenariosEnabled) return featureUnavailable();
        scenarioService.resetScenario(name);
        return ResponseEntity.ok(Map.of("success", true, "scenarioName", name));
    }

    @PutMapping("/scenarios/reset")
    public ResponseEntity<?> resetAllScenarios() {
        if (!scenariosEnabled) return featureUnavailable();
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

    private ResponseEntity<RuleApplyResult> applyResult(HttpStatus status, String operation, String id) {
        RuleApplyDocument resource = findRuleById(id, true)
                .map(ruleApplyMapper::fromRuleDto)
                .orElseThrow(() -> new IllegalStateException("Applied rule not found: " + id));
        return ResponseEntity.status(status).body(new RuleApplyResult(operation, resource));
    }

    /**
     * responseId + responseBody 表示期望內容。內容相同時沿用；不同時建立新回應並重新綁定，
     * 避免修改其他規則共用的 Response。
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

    /**
     * 關閉 Scenario 功能時，不允許建立或變更 Scenario 設定。
     * 既有規則的值可以原樣保存，避免使用者修改其他欄位時意外破壞既有行為。
     */
    private void validateScenarioFeature(RuleDto dto) {
        if (scenariosEnabled) {
            return;
        }
        BaseRule existing = dto.getId() == null
                ? null
                : protocolHandlerRegistry.findById(dto.getId()).orElse(null);
        if (sameScenario(existing, dto)) {
            return;
        }
        if (hasScenario(existing) || hasScenario(dto)) {
            throw new IllegalArgumentException("SCENARIOS_DISABLED");
        }
    }

    private boolean sameScenario(BaseRule existing, RuleDto dto) {
        return existing != null
                && Objects.equals(normalizeScenarioValue(existing.getScenarioName()), normalizeScenarioValue(dto.getScenarioName()))
                && Objects.equals(normalizeScenarioValue(existing.getRequiredScenarioState()), normalizeScenarioValue(dto.getRequiredScenarioState()))
                && Objects.equals(normalizeScenarioValue(existing.getNewScenarioState()), normalizeScenarioValue(dto.getNewScenarioState()));
    }

    private boolean hasScenario(BaseRule rule) {
        return rule != null && (hasText(rule.getScenarioName())
                || hasText(rule.getRequiredScenarioState())
                || hasText(rule.getNewScenarioState()));
    }

    private boolean hasScenario(RuleDto dto) {
        return hasText(dto.getScenarioName())
                || hasText(dto.getRequiredScenarioState())
                || hasText(dto.getNewScenarioState());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeScenarioValue(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private void validateRuleFields(RuleDto dto) {
        validateScenarioFields(dto);
        validateScenarioFeature(dto);
        String value = dto.getFaultType();
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            FaultType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported faultType: " + value);
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
        validateRuleFields(dto);
        Response response;
        boolean faulting = dto.getFaultType() != null
                && !dto.getFaultType().isBlank()
                && !FaultType.NONE.name().equals(dto.getFaultType());
        boolean forwarding = !faulting && dto.getProtocol() == Protocol.HTTP
                && HttpRuleAction.FORWARD.name().equalsIgnoreCase(dto.getAction());
        if (faulting) {
            if (dto.getProtocol() == Protocol.HTTP) {
                dto.setAction(HttpRuleAction.MOCK.name());
            }
            dto.setResponseId(null);
            dto.setResponseBody(null);
            dto.setResponseDescription(null);
            dto.setResponseHeaders(null);
            dto.setForwardTargetMode(null);
            dto.setHttpTargetConnectionId(null);
            dto.setSseEnabled(false);
            dto.setSseLoopEnabled(false);
            response = null;
        } else if (forwarding) {
            httpTargetConnectionService.ifPresent(service -> service.validateForwardSelection(
                    dto.getForwardTargetMode(), dto.getHttpTargetConnectionId()));
            dto.setResponseId(null);
            dto.setResponseBody(null);
            dto.setSseEnabled(false);
            dto.setSseLoopEnabled(false);
            response = null;
        // 使用現有 Response
        } else if (dto.getResponseId() != null && (dto.getResponseBody() == null || dto.getResponseBody().isBlank())) {
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

    static Optional<Path> databaseFilePath(String url) {
        if (url != null && url.startsWith("jdbc:sqlite:")) {
            String configuredPath = url.substring("jdbc:sqlite:".length());
            int queryIndex = configuredPath.indexOf('?');
            if (queryIndex >= 0) configuredPath = configuredPath.substring(0, queryIndex);
            if (":memory:".equals(configuredPath)) {
                return Optional.empty();
            }
            if (!configuredPath.isBlank()) {
                return Optional.of(Paths.get(configuredPath));
            }
        }
        if (url != null && url.startsWith("jdbc:h2:mem:")) {
            return Optional.empty();
        }
        if (url != null && url.startsWith("jdbc:h2:file:")) {
            String configuredPath = url.substring("jdbc:h2:file:".length());
            int optionIndex = configuredPath.indexOf(';');
            if (optionIndex >= 0) configuredPath = configuredPath.substring(0, optionIndex);
            if (!configuredPath.isBlank()) {
                return Optional.of(Paths.get(configuredPath.endsWith(".mv.db")
                        ? configuredPath : configuredPath + ".mv.db"));
            }
        }
        Path sqlite = Paths.get("./mockdb.sqlite");
        if (Files.exists(sqlite)) return Optional.of(sqlite);
        Path h2 = Paths.get("./mockdb.mv.db");
        return Files.exists(h2) ? Optional.of(h2) : Optional.empty();
    }

    private static void putDatabaseFileSize(Map<String, Object> status, Path path) {
        try {
            status.put("dbFileSize", Files.size(path));
        } catch (java.io.IOException ignored) {
            // Status remains available even when the file disappears during inspection.
        }
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
