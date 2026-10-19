package com.echo.controller;

import com.echo.agent.AgentRegistry;
import com.echo.config.CacheConfig;
import com.echo.dto.AgentStatusDto;
import com.echo.entity.BuiltinUser;
import com.echo.entity.Protocol;
import com.echo.protocol.ProtocolHandler;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.BackupService;
import com.echo.service.IssueReportService;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseService;
import com.echo.service.ResponseTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * System status and settings:
 * <ul>
 *   <li>GET /api/admin/status - System status, function switches and data statistics</li>
 *   <li>GET /api/admin/agents - Background work (Agent) status</li>
 *   <li>GET/POST /api/admin/backup - Database backup</li>
 *   <li>POST /api/admin/jms/test - Send JMS test message</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class SystemController {

    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final ResponseService responseService;
    private final Optional<ResponseTemplateService> responseTemplateService;
    private final Optional<UniversalMockController> universalMockController;
    private final RequestLogService requestLogService;
    private final Optional<com.echo.jms.JmsConnectionManager> jmsConnectionManager;
    private final Optional<BackupService> backupService;
    private final BuiltinUserRepository builtinUserRepository;
    private final AgentRegistry agentRegistry;
    private final CacheManager cacheManager;
    private final IssueReportService issueReportService;

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

    @Value("${echo.ui.accent:teal}")
    private String uiAccent;

    /** Interface easter egg (↑↑↓↓←→←→AB or click Echo icon 5 to open a mini-game); the official environment can be closed with ECHO_UI_EASTER_EGG=false. */
    @Value("${echo.ui.easter-egg:true}")
    private boolean uiEasterEgg;

    @Value("${echo.cleanup.response-retention-days:180}")
    private int responseRetentionDays;

    @Value("${echo.request-log.max-records:10000}")
    private int statsMaxRecords;

    @Value("${echo.features.bulk-import-export-enabled:false}")
    private boolean bulkImportExportEnabled;

    @Value("${echo.features.scenarios-enabled:false}")
    private boolean scenariosEnabled;

    @Value("${echo.features.issue-reporting-enabled:false}")
    private boolean issueReportingEnabled;

    @Value("${echo.backup.enabled:false}")
    private boolean backupEnabled;

    @Value("${echo.backup.cron:0 0 3 * * *}")
    private String backupCron;

    @Value("${echo.backup.path:./backups}")
    private String backupPath;

    @Value("${echo.backup.retention-days:7}")
    private int backupRetentionDays;

    private final Instant startupTime = Instant.now();

    /** UI The main color only allows predefined values; blank or unknown values will return to the default cyan. */
    private static final String DEFAULT_UI_ACCENT = "teal";
    private static final Set<String> UI_ACCENTS = Set.of("teal", "blue");

    static String resolveUiAccent(String configured) {
        if (configured == null) {
            return DEFAULT_UI_ACCENT;
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        return UI_ACCENTS.contains(normalized) ? normalized : DEFAULT_UI_ACCENT;
    }

    // ========== System status ==========

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @AuthenticationPrincipal UserDetails user) {
        Map<String, Object> status = new HashMap<>();
        status.put("serverPort", serverPort);
        status.put("sessionTimeout", sessionTimeout);
        status.put("datasourceUrl", datasourceUrl);
        status.put("jmsEnabled", protocolHandlerRegistry.isEnabled(Protocol.JMS));
        status.put("artemisBrokerUrl", "tcp://localhost:" + jmsPort);
        jmsConnectionManager.ifPresent(jms -> {
            status.put("jmsSourceType", jms.sourceType());
            status.put("artemisBrokerUrl", jms.sourceUrl());
            status.put("jmsQueue", jms.getStatus().getQueue());
            var listener = jms.listenerState();
            status.put("jmsListenerReceiving", listener == com.echo.jms.JmsConnectionManager.ListenerState.UNKNOWN
                    ? null : listener == com.echo.jms.JmsConnectionManager.ListenerState.RECEIVING);
        });
        status.put("ldapEnabled", ldapEnabled);
        status.put("selfRegistrationEnabled", selfRegistrationEnabled);
        status.put("bulkImportExportEnabled", bulkImportExportEnabled);
        status.put("scenariosEnabled", scenariosEnabled);
        status.put("issueReportingEnabled", issueReportingEnabled);
        status.put("ldapUrl", ldapUrl);
        status.put("httpAlias", httpAlias);
        status.put("jmsAlias", jmsAlias);
        boolean loggedIn = user != null && !"anonymousUser".equals(user.getUsername());
        status.put("isLoggedIn", loggedIn);
        status.put("isAdmin", loggedIn && user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        status.put("username", loggedIn ? user.getUsername() : null);

        // Built-in account information
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
        status.put("uiAccent", resolveUiAccent(uiAccent));
        status.put("uiEasterEgg", uiEasterEgg);
        status.put("version", getClass().getPackage().getImplementationVersion() != null
                ? getClass().getPackage().getImplementationVersion() : "dev");
        // Checking orphan rules
        status.put("orphanRules", responseService.countOrphanRules());

        // Check orphan response
        status.put("orphanResponses", responseService.countOrphanResponses());

        // Statistics
        status.put("ruleCount", protocolHandlerRegistry.getAllHandlers().stream()
                .mapToLong(ProtocolHandler::count)
                .sum());
        status.put("responseCount", responseService.count());
        status.put("requestLogCount", requestLogService.count());
        status.put("responseRetentionDays", responseRetentionDays);
        status.put("statsMaxRecords", statsMaxRecords);

        // DB file size
        try {
            databaseFilePath(datasourceUrl)
                    .filter(Files::exists)
                    .ifPresent(path -> putDatabaseFileSize(status, path));
        } catch (Exception e) {
            // ignore
        }

        // JVM memory
        Runtime rt = Runtime.getRuntime();
        status.put("jvmHeapUsed", rt.totalMemory() - rt.freeMemory());
        status.put("jvmHeapMax", rt.maxMemory());
        status.put("ruleCaches", getRuleCacheStats());
        status.put("responseBodyCache", responseService.getBodyCacheStats());
        status.put("templateCache", responseTemplateService
                .map(ResponseTemplateService::getTemplateCacheStats).orElse(Map.of()));
        status.put("delaySchedulerQueueSize", universalMockController
                .map(UniversalMockController::getPendingDelayTaskCount).orElse(-1));

        // Startup time
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
        return Optional.empty();
    }

    private static void putDatabaseFileSize(Map<String, Object> status, Path path) {
        try {
            status.put("dbFileSize", Files.size(path));
        } catch (java.io.IOException ignored) {
            // Status remains available even when the file disappears during inspection.
        }
    }

    // ========== Agent Status ==========

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
                        .queueBytes(a.getStats().getQueueBytes())
                        .queueCapacityBytes(a.getStats().getQueueCapacityBytes())
                        .inFlightBytes(a.getStats().getInFlightBytes())
                        .inFlightByteLimit(a.getStats().getInFlightByteLimit())
                        .waitingProducers(a.getStats().getWaitingProducers())
                        .backpressureActive(a.getStats().isBackpressureActive())
                        .build())
                .toList();
        return ResponseEntity.ok(result);
    }

    // ========== Database backup ==========

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

    // ========== JMS Test ==========

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
}
