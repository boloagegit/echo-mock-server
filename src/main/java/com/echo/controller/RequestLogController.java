package com.echo.controller;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.service.RequestLogService;
import com.echo.service.RequestLogTrafficService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Request records: query (can limit time range), details, statistics, traffic overview, clearing, and generate draft rules from a record.
 */
@RestController
@RequestMapping(value = "/api/admin", produces = "application/json")
@RequiredArgsConstructor
public class RequestLogController {

    private final RequestLogService requestLogService;
    private final RequestLogTrafficService trafficService;

    @GetMapping("/logs")
    public ResponseEntity<RequestLogService.SummaryQueryResult> queryLogs(
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) Boolean matched,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, name = "sort") String sortField,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        RequestLogService.QueryFilter filter = RequestLogService.QueryFilter.builder()
                .ruleId(ruleId)
                .protocol(protocol != null ? Protocol.valueOf(protocol) : null)
                .matched(matched)
                .endpoint(endpoint)
                .method(method)
                .path(path)
                .page(page)
                .size(size != null ? size : limit)
                .sortField(sortField)
                .sortDirection(direction)
                .afterId(afterId)
                .from(from)
                .to(to)
                .build();
        return ResponseEntity.ok(requestLogService.querySummary(filter));
    }

    /** Reserved for compatibility with existing Java call-side and unit tests. */
    public ResponseEntity<RequestLogService.SummaryQueryResult> queryLogs(
            String ruleId, String protocol, Boolean matched, String endpoint) {
        return queryLogs(ruleId, protocol, matched, endpoint,
                null, null, null, null, null, null);
    }

    /** Compatible entry with no time range. */
    public ResponseEntity<RequestLogService.SummaryQueryResult> queryLogs(
            String ruleId, String protocol, Boolean matched, String endpoint, Integer page, Integer size,
            Integer limit, String sortField, String direction, Long afterId) {
        return queryLogs(ruleId, protocol, matched, endpoint, null, null, page, size, limit, sortField, direction,
                afterId, null, null);
    }

    /**
     * Traffic profile: {@code range} is 1h, 24h or 7d. The results are shared briefly (see {@link RequestLogTrafficService}),
     * If many people turn it on at the same time, it will only be counted once.
     */
    @GetMapping("/logs/traffic")
    public ResponseEntity<RequestLogTrafficService.Traffic> getTraffic(
            @RequestParam(defaultValue = "24h") String range) {
        return ResponseEntity.ok(trafficService.traffic(RequestLogTrafficService.Range.of(range)));
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
        trafficService.invalidate();
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
