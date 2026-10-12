package com.echo.controller;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.service.RequestLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 請求紀錄：查詢、明細、統計、清除，以及從一筆紀錄產生規則草稿。
 */
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class RequestLogController {

    private final RequestLogService requestLogService;

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
                            .matchKey(entry.getProtocol() == Protocol.JMS
                                    ? "*" : entry.getEndpoint())
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
}
