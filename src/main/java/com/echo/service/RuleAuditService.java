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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
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
            RuleAuditLog audit = RuleAuditLog.builder()
                    .ruleId(ruleId)
                    .action(Action.UPDATE)
                    .beforeJson(beforeJson)
                    .afterJson(afterJson)
                    .operator(getCurrentUser())
                    .timestamp(LocalDateTime.now())
                    .build();
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
                audits.add(RuleAuditLog.builder()
                        .ruleId(ruleId)
                        .action(Action.UPDATE)
                        .beforeJson(beforeJsonList.get(i))
                        .afterJson(afterJsonList.get(i))
                        .operator(operator)
                        .timestamp(now)
                        .build());
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
            RuleAuditLog audit = RuleAuditLog.builder()
                    .ruleId(after != null ? "response-" + after.getId() : "response-" + before.getId())
                    .action(action)
                    .beforeJson(beforeJson)
                    .afterJson(afterJson)
                    .operator(getCurrentUser())
                    .timestamp(LocalDateTime.now())
                    .build();
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
            RuleAuditLog audit = RuleAuditLog.builder()
                    .ruleId(after != null ? after.getId() : before.getId())
                    .action(action)
                    .beforeJson(beforeJson)
                    .afterJson(afterJson)
                    .operator(getCurrentUser())
                    .timestamp(LocalDateTime.now())
                    .build();
            repository.save(audit);
            log.debug("Audit log: {} rule {} by {}", action, audit.getRuleId(), audit.getOperator());
        } catch (Exception e) {
            log.warn("Failed to create audit log: {}", e.getMessage());
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
        return repository.findByRuleIdOrderByTimestampDesc(ruleId, PageRequest.of(0, limit));
    }

    public List<RuleAuditLog> getAllAuditLogs(int limit) {
        return repository.findAllByOrderByTimestampDesc(PageRequest.of(0, limit));
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
