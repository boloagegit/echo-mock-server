package com.echo.controller;

import com.echo.config.CacheConfig;
import com.echo.core.match.InvalidMatchSpecException;
import com.echo.core.match.LegacyConditions;
import com.echo.core.match.MatchSpecCodec;
import com.echo.dto.RuleDto;
import com.echo.dto.RuleGroupSummaryDto;
import com.echo.dto.RulePageDto;
import com.echo.entity.Protocol;
import com.echo.protocol.ProtocolDescriptor;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.service.CacheInvalidationService;
import com.echo.service.MatchSpecs;
import com.echo.service.RuleAuditService;
import com.echo.service.RuleEditService;
import com.echo.service.RuleQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 規則的查詢、建立、修改、刪除與測試：
 * <ul>
 *   <li>GET /api/admin/rules、/rules/page、/rules/groups、/rules/group - 規則列表與分組</li>
 *   <li>GET/POST/PUT/DELETE /api/admin/rules/{id} - 單一規則</li>
 *   <li>DELETE /api/admin/rules/batch、/rules/all - 批次刪除</li>
 *   <li>POST /api/admin/rules/match/parse、/rules/match/validate - 結構化條件編輯器</li>
 *   <li>POST /api/admin/rules/{id}/test - 測試規則</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class RuleController {

    private final RuleQueryService ruleQueryService;
    private final ProtocolHandlerRegistry protocolHandlerRegistry;
    private final RuleEditService ruleEditService;
    private final Optional<RuleAuditService> ruleAuditService;
    private final Optional<CacheInvalidationService> cacheInvalidationService;

    @Value("${server.port:8080}")
    private int serverPort;

    // ========== 查詢 ==========

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
            @RequestParam(required = false) RuleQueryService.RuleMode mode,
            @RequestParam(required = false) Boolean expiring,
            @RequestParam(required = false) Boolean unreachable,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false, name = "sort") String sortField,
            @RequestParam(required = false) String direction) {
        var result = ruleQueryService.query(new RuleQueryService.RuleQuery(
                protocol, enabled, isProtected, keyword, page, size, sortField, direction, mode, expiring, unreachable));
        List<RuleDto> rules = result.rules().stream()
                .map(rule -> protocolHandlerRegistry.toDto(rule, null))
                .toList();
        return ResponseEntity.ok(new RulePageDto(rules, result.page(), result.size(),
                result.totalElements(), result.totalPages()));
    }

    ResponseEntity<RulePageDto> queryRules(Protocol protocol, Boolean enabled, Boolean isProtected,
                                           String keyword, int page, int size,
                                           String sortField, String direction) {
        return queryRules(protocol, enabled, isProtected, null, null, null, keyword,
                page, size, sortField, direction);
    }

    @GetMapping("/rules/groups")
    public ResponseEntity<RuleGroupSummaryDto> queryRuleGroups(
            @RequestParam(required = false) Protocol protocol,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean isProtected,
            @RequestParam(required = false) RuleQueryService.RuleMode mode,
            @RequestParam(required = false) Boolean expiring,
            @RequestParam(required = false) Boolean unreachable,
            @RequestParam(required = false) String keyword) {
        var result = ruleQueryService.queryGroupSummary(new RuleQueryService.RuleQuery(
                protocol, enabled, isProtected, keyword, 0, 20, null, null, mode, expiring, unreachable));
        return ResponseEntity.ok(new RuleGroupSummaryDto(
                result.tagKeys(), result.counts(), result.totalElements()));
    }

    ResponseEntity<RuleGroupSummaryDto> queryRuleGroups(Protocol protocol, Boolean enabled,
                                                        Boolean isProtected, String keyword) {
        return queryRuleGroups(protocol, enabled, isProtected, null, null, null, keyword);
    }

    @GetMapping("/rules/group")
    public ResponseEntity<RulePageDto> queryRuleGroup(
            @RequestParam String key,
            @RequestParam(required = false) String value,
            @RequestParam(required = false) Protocol protocol,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean isProtected,
            @RequestParam(required = false) RuleQueryService.RuleMode mode,
            @RequestParam(required = false) Boolean expiring,
            @RequestParam(required = false) Boolean unreachable,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false, name = "sort") String sortField,
            @RequestParam(required = false) String direction) {
        int effectiveLimit = Math.max(1, limit);
        var result = ruleQueryService.queryGroup(new RuleQueryService.RuleQuery(
                protocol, enabled, isProtected, keyword, 0, effectiveLimit, sortField, direction, mode, expiring, unreachable),
                key, value, effectiveLimit);
        List<RuleDto> rules = result.rules().stream()
                .map(rule -> protocolHandlerRegistry.toDto(rule, null))
                .toList();
        int totalPages = result.totalElements() == 0 ? 0
                : (int) Math.ceil((double) result.totalElements() / effectiveLimit);
        return ResponseEntity.ok(new RulePageDto(
                rules, 0, effectiveLimit, result.totalElements(), totalPages));
    }

    ResponseEntity<RulePageDto> queryRuleGroup(String key, String value, Protocol protocol,
                                               Boolean enabled, Boolean isProtected, String keyword,
                                               int limit, String sortField, String direction) {
        return queryRuleGroup(key, value, protocol, enabled, isProtected, null, null, null,
                keyword, limit, sortField, direction);
    }

    // ========== 單一規則 ==========

    @GetMapping("/rules/{id}")
    public ResponseEntity<RuleDto> getRule(@PathVariable String id) {
        return ruleEditService.findDto(id, true)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/rules/{id}/json")
    public ResponseEntity<RuleDto> exportRule(@PathVariable String id) {
        return ruleEditService.findDto(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/rules")
    public ResponseEntity<?> createRule(@RequestBody RuleDto dto) {
        ruleEditService.requireProtocolEnabled(dto.getProtocol());
        ruleEditService.validateScenarioFields(dto);
        dto.setId(null);
        RuleEditService.Saved result = ruleEditService.save(dto);
        ruleAuditService.ifPresent(s -> s.logCreate(result.rule()));
        return ResponseEntity.status(HttpStatus.CREATED).body(result.dto());
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<?> updateRule(@PathVariable String id, @RequestBody RuleDto dto) {
        ruleEditService.requireProtocolEnabled(dto.getProtocol());
        ruleEditService.validateScenarioFields(dto);
        return protocolHandlerRegistry.findById(id)
                .map(existing -> {
                    dto.setId(id);
                    dto.setVersion(existing.getVersion());
                    dto.setCreatedAt(existing.getCreatedAt()); // 保留建立時間
                    // 在 save 之前先序列化快照，避免 JPA merge 後 existing 被覆蓋
                    String beforeJson = ruleAuditService.map(s -> s.snapshot(existing)).orElse(null);
                    try {
                        RuleEditService.Saved result = ruleEditService.save(dto);
                        ruleAuditService.ifPresent(s -> {
                            if (beforeJson != null) {
                                s.logUpdate(beforeJson, s.snapshot(result.rule()));
                            } else {
                                s.logUpdate(existing, result.rule());
                            }
                        });
                        return ResponseEntity.ok(result.dto());
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
                    ruleEditService.rulesChanged(protocol);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ========== 批次刪除 ==========

    @DeleteMapping("/rules/batch")
    @CacheEvict(cacheNames = { CacheConfig.HTTP_RULES_CACHE, CacheConfig.JMS_RULES_CACHE }, allEntries = true)
    public ResponseEntity<?> deleteRules(@RequestBody List<String> ids) {
        int deleted = 0;
        for (String id : ids) {
            var rule = ruleEditService.findDto(id);
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

    // ========== 結構化條件 ==========

    /** 結構化條件編輯器使用的請求：協定，加上舊版三個條件字串或 matchSpec。 */
    public record MatchRequest(Protocol protocol, String bodyCondition, String queryCondition,
                               String headerCondition, JsonNode matchSpec) {
    }

    /** 把舊版條件字串轉成只含舊版片段的結構化條件（切法與比對時相同），供編輯器切換到進階模式。 */
    @PostMapping("/rules/match/parse")
    public Map<String, Object> parseMatch(@RequestBody MatchRequest request) {
        ruleEditService.requireProtocolEnabled(request.protocol());
        return Map.of("matchSpec", MatchSpecCodec.toJson(LegacyConditions.toMatchNode(
                request.bodyCondition(), request.queryCondition(), request.headerCondition())));
    }

    /**
     * 以與儲存時相同的規則檢查結構化條件，不寫入任何資料，供編輯器即時提示。
     * 檢查本身一律回 200：{@code valid=false} 時附錯誤訊息與 JSON 路徑；{@code valid=true} 時回傳正規化結果
     * （可用舊版字串表達時 {@code advanced=false} 並附上三個字串）。
     */
    @PostMapping("/rules/match/validate")
    public ResponseEntity<Map<String, Object>> validateMatch(@RequestBody MatchRequest request) {
        ruleEditService.requireProtocolEnabled(request.protocol());
        ProtocolDescriptor protocol = protocolHandlerRegistry.descriptor(request.protocol())
                .orElseThrow(() -> new IllegalArgumentException("Unknown protocol: " + request.protocol()));
        MatchSpecs.Normalized normalized;
        try {
            normalized = MatchSpecs.normalize(request.matchSpec(), protocol.matchSources());
        } catch (InvalidMatchSpecException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("error", e.getMessage());
            error.put("path", e.path());
            return ResponseEntity.ok(error);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("valid", true);
        result.put("advanced", normalized.advanced());
        result.put("matchSpec", normalized.advanced() ? MatchSpecs.read(normalized.matchSpec()).orElse(null) : null);
        result.put("bodyCondition", normalized.bodyCondition());
        result.put("queryCondition", normalized.queryCondition());
        result.put("headerCondition", normalized.headerCondition());
        result.put("conditionCount", MatchSpecs.conditionCount(request.matchSpec()));
        return ResponseEntity.ok(result);
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
