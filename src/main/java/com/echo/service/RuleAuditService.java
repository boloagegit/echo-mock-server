package com.echo.service;

import com.echo.entity.BaseRule;
import com.echo.entity.Response;
import com.echo.entity.RuleAuditLog;
import com.echo.entity.RuleAuditLog.Action;
import com.echo.repository.RuleAuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 規則審計服務 - 記錄規則變更歷史
 * 僅在 Database 模式啟用
 */
@Service
@ConditionalOnProperty(name = "echo.storage.mode", havingValue = "database", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RuleAuditService {

    private final RuleAuditLogRepository repository;
    private final SystemConfigService configService;
    private static final Set<String> TARGET_TYPES = Set.of("RULE", "RESPONSE");
    private static final int BACKFILL_BATCH_SIZE = 50;
    private static final int BACKFILL_BATCHES_PER_RUN = 10;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 比較時忽略的欄位（每次 save 都會變動） */
    private static final Set<String> IGNORED_FIELDS = Set.of(
            "version", "updatedAt", "extendedAt", "bodySize"
    );

    public void logCreate(BaseRule rule) {
        log(null, rule, Action.CREATE);
    }

    /**
     * 將規則序列化為 JSON 快照（用於在 JPA save 前保存狀態）
     */
    public String snapshot(BaseRule rule) {
        try {
            return objectMapper.writeValueAsString(rule);
        } catch (Exception e) {
            log.warn("Failed to snapshot rule: {}", e.getMessage());
            return null;
        }
    }

    public void logUpdate(String beforeJson, String afterJson) {
        try {
            if (!hasActualChanges(beforeJson, afterJson)) {
                log.debug("Audit log skipped: no actual changes");
                return;
            }
            var node = objectMapper.readTree(afterJson);
            String ruleId = node.has("id") ? node.get("id").asText() : null;
            RuleAuditLog audit = newAudit(ruleId, Action.UPDATE, beforeJson, afterJson,
                    getCurrentUser(), LocalDateTime.now());
            repository.save(audit);
            log.debug("Audit log: UPDATE rule {} by {}", audit.getRuleId(), audit.getOperator());
        } catch (Exception e) {
            log.warn("Failed to create audit log: {}", e.getMessage());
        }
    }

    public void logBatchUpdate(List<String> beforeJsonList, List<String> afterJsonList) {
        try {
            String operator = getCurrentUser();
            LocalDateTime now = LocalDateTime.now();
            List<RuleAuditLog> audits = new ArrayList<>();
            
            for (int i = 0; i < afterJsonList.size(); i++) {
                if (!hasActualChanges(beforeJsonList.get(i), afterJsonList.get(i))) {
                    continue;
                }
                var node = objectMapper.readTree(afterJsonList.get(i));
                String ruleId = node.has("id") ? node.get("id").asText() : null;
                audits.add(newAudit(ruleId, Action.UPDATE, beforeJsonList.get(i), afterJsonList.get(i),
                        operator, now));
            }
            if (!audits.isEmpty()) {
                repository.saveAll(audits);
                log.debug("Audit log: batch UPDATE {} rules by {}", audits.size(), operator);
            }
        } catch (Exception e) {
            log.warn("Failed to create batch audit log: {}", e.getMessage());
        }
    }

    public void logDelete(BaseRule rule) {
        log(rule, null, Action.DELETE);
    }

    public void logUpdate(BaseRule before, BaseRule after) {
        log(before, after, Action.UPDATE);
    }

    public void logResponseCreate(Response response) {
        logResponse(null, response, Action.CREATE);
    }

    public void logResponseUpdate(Response before, Response after) {
        logResponse(before, after, Action.UPDATE);
    }

    public void logResponseDelete(Response response) {
        logResponse(response, null, Action.DELETE);
    }

    private void logResponse(Response before, Response after, Action action) {
        try {
            String beforeJson = before != null ? objectMapper.writeValueAsString(before) : null;
            String afterJson = after != null ? objectMapper.writeValueAsString(after) : null;
            if (action == Action.UPDATE && !hasActualChanges(beforeJson, afterJson)) {
                log.debug("Audit log skipped: no actual changes for response {}", 
                        after != null ? after.getId() : (before != null ? before.getId() : null));
                return;
            }
            RuleAuditLog audit = newAudit(
                    after != null ? "response-" + after.getId() : "response-" + before.getId(),
                    action, beforeJson, afterJson, getCurrentUser(), LocalDateTime.now());
            repository.save(audit);
            log.debug("Audit log: {} response {} by {}", action, audit.getRuleId(), audit.getOperator());
        } catch (Exception e) {
            log.warn("Failed to create audit log: {}", e.getMessage());
        }
    }

    private void log(BaseRule before, BaseRule after, Action action) {
        try {
            String beforeJson = before != null ? objectMapper.writeValueAsString(before) : null;
            String afterJson = after != null ? objectMapper.writeValueAsString(after) : null;
            if (action == Action.UPDATE && !hasActualChanges(beforeJson, afterJson)) {
                log.debug("Audit log skipped: no actual changes for rule {}", 
                        after != null ? after.getId() : (before != null ? before.getId() : null));
                return;
            }
            RuleAuditLog audit = newAudit(after != null ? after.getId() : before.getId(),
                    action, beforeJson, afterJson, getCurrentUser(), LocalDateTime.now());
            repository.save(audit);
            log.debug("Audit log: {} rule {} by {}", action, audit.getRuleId(), audit.getOperator());
        } catch (Exception e) {
            log.warn("Failed to create audit log: {}", e.getMessage());
        }
    }

    /** 建立修訂記錄，同時算好列表摘要，列表就不必讀取前後快照。 */
    private RuleAuditLog newAudit(String ruleId, Action action, String beforeJson, String afterJson,
                                  String operator, LocalDateTime timestamp) {
        AuditSummaryCalculator.Result summary = summarize(ruleId, action, beforeJson, afterJson);
        return RuleAuditLog.builder()
                .ruleId(ruleId)
                .action(action)
                .beforeJson(beforeJson)
                .afterJson(afterJson)
                .operator(operator)
                .timestamp(timestamp)
                .targetProtocol(summary.protocol())
                .targetName(summary.name())
                .targetDescription(summary.description())
                .summary(summary.summary())
                .build();
    }

    private AuditSummaryCalculator.Result summarize(String ruleId, Action action, String beforeJson, String afterJson) {
        try {
            return AuditSummaryCalculator.compute(objectMapper, ruleId, action, beforeJson, afterJson);
        } catch (RuntimeException e) {
            log.debug("Audit summary skipped for {}: {}", ruleId, e.getMessage());
            return AuditSummaryCalculator.Result.EMPTY;
        }
    }

    /**
     * 比較兩個 JSON 字串是否有實質變更（排除 version、updatedAt 等自動欄位）
     */
    boolean hasActualChanges(String beforeJson, String afterJson) {
        if (beforeJson == null || afterJson == null) {
            return true;
        }
        try {
            JsonNode beforeNode = objectMapper.readTree(beforeJson);
            JsonNode afterNode = objectMapper.readTree(afterJson);
            if (beforeNode.isObject() && afterNode.isObject()) {
                ObjectNode beforeCopy = ((ObjectNode) beforeNode).deepCopy();
                ObjectNode afterCopy = ((ObjectNode) afterNode).deepCopy();
                for (String field : IGNORED_FIELDS) {
                    beforeCopy.remove(field);
                    afterCopy.remove(field);
                }
                return !beforeCopy.equals(afterCopy);
            }
            return !beforeNode.equals(afterNode);
        } catch (Exception e) {
            // JSON 解析失敗，保守起見視為有變更
            return true;
        }
    }

    private String getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return "anonymous";
    }

    public List<RuleAuditLog> getAuditLogs(String ruleId, int limit) {
        return repository.findSummaryByRuleId(ruleId, PageRequest.of(0, limit)).stream()
                .map(this::mapSummaryRow)
                .toList();
    }

    public List<RuleAuditLog> getAllAuditLogs(int limit) {
        return repository.findAllSummary(PageRequest.of(0, limit)).stream()
                .map(this::mapSummaryRow)
                .toList();
    }

    /** 查詢修訂記錄摘要；完整 JSON 由 getAuditDetail() 單筆載入。 */
    public AuditQueryResult queryAuditSummary(AuditQueryFilter filter) {
        int pageNumber = Math.max(0, filter.getPage() != null ? filter.getPage() : 0);
        int pageSize = Math.max(1, Math.min(filter.getSize() != null ? filter.getSize() : 20, 100));
        String sortField = normalizeSortField(filter.getSortField());
        Sort.Direction direction = "asc".equalsIgnoreCase(filter.getSortDirection())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, sortField).and(Sort.by(direction, "id"));

        Page<Object[]> resultPage = repository.findSummaryPage(
                filter.getAction(), normalizeFilter(filter.getOperator()),
                normalizeFilter(filter.getKeyword()), normalizeTargetType(filter.getTargetType()),
                PageRequest.of(pageNumber, pageSize, sort));

        return AuditQueryResult.builder()
                .results(resultPage.getContent().stream().map(this::mapAuditSummaryRow).toList())
                .page(pageNumber)
                .size(pageSize)
                .totalElements(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .build();
    }

    public RuleAuditLog getAuditDetail(long id) {
        return repository.findById(id).orElse(null);
    }

    private String normalizeSortField(String field) {
        return switch (field == null ? "" : field) {
            case "action", "operator", "ruleId" -> field;
            default -> "timestamp";
        };
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeTargetType(String value) {
        String type = normalizeFilter(value);
        if (type == null) {
            return null;
        }
        type = type.toUpperCase(java.util.Locale.ROOT);
        if (!TARGET_TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be RULE or RESPONSE");
        }
        return type;
    }

    private AuditSummary mapAuditSummaryRow(Object[] row) {
        long id = ((Number) row[0]).longValue();
        String protocol = (String) row[7];
        String name = (String) row[8];
        String description = (String) row[9];
        String summary = (String) row[10];
        if (summary == null) {
            // 舊資料：這一筆現在補算並存回去，之後就不必再算。
            AuditSummaryCalculator.Result filled = fillSummary(id);
            if (filled != null) {
                protocol = filled.protocol();
                name = filled.name();
                description = filled.description();
                summary = filled.summary();
            }
        }
        return AuditSummary.builder()
                .id(id)
                .ruleId((String) row[1])
                .action((RuleAuditLog.Action) row[2])
                .operator((String) row[3])
                .timestamp((LocalDateTime) row[4])
                .hasBeforeJson(Boolean.TRUE.equals(row[5]))
                .hasAfterJson(Boolean.TRUE.equals(row[6]))
                .targetProtocol(protocol)
                .targetName(name)
                .targetDescription(description)
                .summary(parseSummary(summary))
                .build();
    }

    private JsonNode parseSummary(String summary) {
        if (summary == null) {
            return null;
        }
        try {
            return objectMapper.readTree(summary);
        } catch (Exception e) {
            return null;
        }
    }

    /** 補算一筆舊資料的列表摘要；一次只載入一筆快照，避免大型回應內容同時佔用記憶體。 */
    AuditSummaryCalculator.Result fillSummary(long id) {
        try {
            RuleAuditLog audit = repository.findById(id).orElse(null);
            if (audit == null) {
                return null;
            }
            if (audit.getSummary() != null) {
                return new AuditSummaryCalculator.Result(audit.getTargetProtocol(), audit.getTargetName(),
                        audit.getTargetDescription(), audit.getSummary());
            }
            AuditSummaryCalculator.Result result = summarize(audit.getRuleId(), audit.getAction(),
                    audit.getBeforeJson(), audit.getAfterJson());
            repository.fillSummary(id, result.protocol(), result.name(), result.description(), result.summary());
            return result;
        } catch (RuntimeException e) {
            log.warn("Failed to fill audit summary {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 背景補算升級前的修訂記錄摘要，讓用名稱搜尋也找得到舊資料。
     * 每輪最多處理 {@value #BACKFILL_BATCHES_PER_RUN} 批、每批 {@value #BACKFILL_BATCH_SIZE} 筆，
     * 而且一次只載入一筆，不會在啟動時一口氣掃過整張表。
     */
    @Scheduled(initialDelayString = "${echo.audit.summary-backfill-initial-delay-ms:30000}",
            fixedDelayString = "${echo.audit.summary-backfill-delay-ms:60000}")
    public void backfillSummaries() {
        int filled = 0;
        for (int batch = 0; batch < BACKFILL_BATCHES_PER_RUN; batch++) {
            List<Long> ids = repository.findIdsWithoutSummary(PageRequest.of(0, BACKFILL_BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            int before = filled;
            for (Long id : ids) {
                if (fillSummary(id) != null) {
                    filled++;
                }
            }
            if (filled == before) {
                // 這一批全部失敗，下一輪再試，避免在同一輪反覆讀同樣的資料。
                break;
            }
        }
        if (filled > 0) {
            log.info("Filled list summaries for {} older audit logs", filled);
        }
    }

    /** Maps Object[] from findSummaryByRuleId/findAllSummary: [id, ruleId, action, operator, timestamp] */
    private RuleAuditLog mapSummaryRow(Object[] row) {
        return RuleAuditLog.builder()
                .id((Long) row[0])
                .ruleId((String) row[1])
                .action((RuleAuditLog.Action) row[2])
                .operator((String) row[3])
                .timestamp((LocalDateTime) row[4])
                .build();
    }

    @Getter
    @Builder
    public static class AuditQueryFilter {
        private RuleAuditLog.Action action;
        private String operator;
        private String keyword;
        private String targetType;
        private Integer page;
        private Integer size;
        private String sortField;
        private String sortDirection;
    }

    @Getter
    @Builder
    public static class AuditSummary {
        private Long id;
        private String ruleId;
        private RuleAuditLog.Action action;
        private String operator;
        private LocalDateTime timestamp;
        private boolean hasBeforeJson;
        private boolean hasAfterJson;
        private String targetProtocol;
        private String targetName;
        private String targetDescription;
        private JsonNode summary;
    }

    @Getter
    @Builder
    public static class AuditQueryResult {
        private List<AuditSummary> results;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }

    @Transactional
    public long deleteAll() {
        long count = repository.count();
        repository.deleteAll();
        log.info("Deleted all {} audit logs", count);
        return count;
    }

    @Scheduled(cron = "0 30 2 * * ?")
    @Transactional
    public void cleanup() {
        int retentionDays = configService.getAuditRetentionDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = repository.deleteByTimestampBefore(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} audit logs older than {} days", deleted, retentionDays);
        }
    }
}
