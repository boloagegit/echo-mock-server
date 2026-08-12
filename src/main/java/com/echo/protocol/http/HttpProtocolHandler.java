package com.echo.protocol.http;

import com.echo.dto.RuleDto;
import com.echo.entity.BaseRule;
import com.echo.entity.FaultType;
import com.echo.entity.HttpRule;
import com.echo.entity.HttpRuleAction;
import com.echo.entity.HttpForwardTargetMode;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.protocol.AbstractProtocolHandler;
import com.echo.repository.HttpRuleRepository;
import com.echo.service.ContentTypeConstraints;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP 協定處理器
 */
@Component
@RequiredArgsConstructor
public class HttpProtocolHandler extends AbstractProtocolHandler {

    private final HttpRuleRepository repository;

    @Override
    public Protocol getProtocol() {
        return Protocol.HTTP;
    }

    @Override
    public RuleDto toDto(BaseRule rule, Response response, boolean includeBody) {
        HttpRule r = (HttpRule) rule;
        var builder = RuleDto.builder()
                .id(r.getId())
                .version(r.getVersion())
                .protocol(Protocol.HTTP)
                .targetHost(r.getTargetHost())
                .matchKey(r.getMatchKey())
                .method(r.getMethod())
                .bodyCondition(r.getBodyCondition())
                .queryCondition(r.getQueryCondition())
                .headerCondition(r.getHeaderCondition())
                .priority(r.getPriority())
                .description(r.getDescription())
                .enabled(r.getEnabled())
                .isProtected(r.getIsProtected())
                .tags(r.getTags())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .extendedAt(r.getExtendedAt())
                .condition(r.getCondition())
                .responseId(r.getResponseId())
                .status(r.getHttpStatus())
                .responseHeaders(r.getHttpHeaders())
                .delayMs(r.getDelayMs())
                .maxDelayMs(r.getMaxDelayMs())
                .sseEnabled(r.getSseEnabled())
                .sseLoopEnabled(r.getSseLoopEnabled())
                .responseContentType(ContentTypeConstraints.infer(Protocol.HTTP, r.getSseEnabled()).name())
                .action(r.getAction() == null ? HttpRuleAction.MOCK.name() : r.getAction().name())
                .forwardTargetMode(r.getForwardTargetMode() == null
                        ? HttpForwardTargetMode.ORIGINAL_HOST.name() : r.getForwardTargetMode().name())
                .httpTargetConnectionId(r.getHttpTargetConnectionId())
                .faultType(r.getFaultType() != null ? r.getFaultType().name() : "NONE")
                .scenarioName(r.getScenarioName())
                .requiredScenarioState(r.getRequiredScenarioState())
                .newScenarioState(r.getNewScenarioState());
        if (includeBody && response != null) {
            builder.responseBody(response.getBody());
        }
        return builder.build();
    }

    @Override
    public BaseRule fromDto(RuleDto dto) {
        return HttpRule.builder()
                .id(dto.getId())
                .version(dto.getVersion())
                .action(parseAction(dto.getAction()))
                .forwardTargetMode(parseForwardTargetMode(dto.getForwardTargetMode()))
                .httpTargetConnectionId(dto.getHttpTargetConnectionId())
                .targetHost(dto.getTargetHost())
                .matchKey(dto.getMatchKey())
                .method(dto.getMethod())
                .bodyCondition(dto.getBodyCondition())
                .queryCondition(dto.getQueryCondition())
                .headerCondition(dto.getHeaderCondition())
                .priority(dto.getPriority() != null ? dto.getPriority() : 0)
                .description(dto.getDescription() != null && !dto.getDescription().isBlank() ? dto.getDescription() : generateRuleDescription(dto))
                .enabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                .isProtected(dto.getIsProtected() != null ? dto.getIsProtected() : false)
                .tags(dto.getTags())
                .responseId(dto.getResponseId())
                .httpStatus(dto.getStatus() != null ? dto.getStatus() : 200)
                .httpHeaders(dto.getResponseHeaders())
                .delayMs(dto.getDelayMs() != null ? dto.getDelayMs() : 0L)
                .maxDelayMs(dto.getMaxDelayMs())
                .sseEnabled(dto.getSseEnabled() != null ? dto.getSseEnabled() : false)
                .sseLoopEnabled(dto.getSseLoopEnabled() != null ? dto.getSseLoopEnabled() : false)
                .faultType(parseFaultType(dto.getFaultType()))
                .scenarioName(dto.getScenarioName())
                .requiredScenarioState(dto.getRequiredScenarioState())
                .newScenarioState(dto.getNewScenarioState())
                .createdAt(dto.getCreatedAt()) // 保留建立時間
                .build();
    }

    private static HttpRuleAction parseAction(String value) {
        return value == null || value.isBlank()
                ? HttpRuleAction.MOCK : HttpRuleAction.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    private static HttpForwardTargetMode parseForwardTargetMode(String value) {
        return value == null || value.isBlank() ? HttpForwardTargetMode.ORIGINAL_HOST
                : HttpForwardTargetMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public List<HttpRule> findAllRules() {
        return repository.findAll();
    }

    @Override
    public Optional<HttpRule> findById(String id) {
        return repository.findById(id);
    }

    @Override
    public List<HttpRule> findAllByIds(List<String> ids) {
        return repository.findAllById(ids);
    }


    @Override
    public BaseRule save(BaseRule rule) {
        return repository.save((HttpRule) rule);
    }

    @Override
    public void deleteById(String id) {
        repository.deleteById(id);
    }

    /**
     * 查詢 HTTP 規則（含萬用 fallback）
     */
    public List<HttpRule> findWithFallback(String targetHost, String matchKey, String method) {
        List<HttpRule> result = new ArrayList<>();
        for (HttpRule rule : findByHostAndMethod(targetHost, method)) {
            if (matchesPath(rule.getMatchKey(), matchKey)) {
                result.add(rule);
            } else if ("*".equals(rule.getMatchKey())) {
                result.add(rule);
            }
        }
        return result;
    }

    /**
     * 查詢指定來源主機與 HTTP 方法可能使用的規則，但不綁定實際 request path。
     * 呼叫端可將此結果以 host + method 快取，避免動態 URL 每個 path 都建立一份快取。
     */
    public List<HttpRule> findByHostAndMethod(String targetHost, String method) {
        List<HttpRule> result = new ArrayList<>();
        for (HttpRule rule : repository.findByTargetHostOrWildcard(targetHost)) {
            boolean hostMatches = targetHost.equals(rule.getTargetHost())
                    || rule.getTargetHost() == null || rule.getTargetHost().isEmpty();
            if (hostMatches && matchesMethod(rule.getMethod(), method)) {
                result.add(rule);
            }
        }
        return result;
    }

    /** 保留既有 exact、prefix wildcard 與全域 wildcard 的路徑語意。 */
    public boolean matchesRequestPath(String pattern, String path) {
        return "*".equals(pattern) || matchesPath(pattern, path);
    }

    private boolean matchesPath(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        if (pattern.equals(path)) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return false;
    }

    private boolean matchesMethod(String ruleMethod, String requestMethod) {
        return ruleMethod == null || ruleMethod.equalsIgnoreCase(requestMethod);
    }

    private FaultType parseFaultType(String value) {
        if (value == null || value.isBlank()) {
            return FaultType.NONE;
        }
        try {
            return FaultType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return FaultType.NONE;
        }
    }

    @Override
    public int deleteAll() {
        int count = (int) repository.count();
        repository.deleteAll();
        return count;
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public int deleteExpiredRules(LocalDateTime cutoff) {
        return repository.deleteExpiredRules(cutoff, false);
    }

    @Override
    public List<Object[]> countGroupByResponseId() {
        return repository.countGroupByResponseId();
    }

    @Override
    public int deleteByResponseId(Long responseId) {
        return repository.deleteByResponseId(responseId);
    }

    @Override
    public List<HttpRule> findByResponseId(Long responseId) {
        return repository.findByResponseId(responseId);
    }

    @Override
    public long countOrphanRules() {
        return repository.countOrphanRules();
    }

    @Override
    public int updateEnabled(List<String> ids, boolean enabled) {
        return repository.updateEnabledByIds(ids, enabled);
    }

    @Override
    public int updateProtected(List<String> ids, boolean isProtected) {
        return repository.updateProtectedByIds(ids, isProtected);
    }

    @Override
    public int extendRules(List<String> ids, LocalDateTime extendedAt) {
        return repository.extendByIds(ids, extendedAt);
    }

    @Override
    public String generateDescription(RuleDto dto) {
        return dto.getMethod() + " " + dto.getMatchKey();
    }

    @Override
    public List<String> findIdsByTagPattern(String pattern) {
        return repository.findIdsByTagPattern(pattern);
    }

    private String generateRuleDescription(RuleDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append(dto.getMethod()).append(" ").append(dto.getMatchKey());
        if (dto.getTargetHost() != null && !dto.getTargetHost().isBlank()) {
            sb.append(" @ ").append(dto.getTargetHost());
        }
        if (FaultType.CONNECTION_RESET.name().equals(dto.getFaultType())) {
            sb.append(" → Connection reset");
        } else if (FaultType.EMPTY_RESPONSE.name().equals(dto.getFaultType())) {
            sb.append(" → ").append(dto.getStatus() != null ? dto.getStatus() : 200).append(" (empty)");
        } else if (HttpRuleAction.FORWARD.name().equalsIgnoreCase(dto.getAction())) {
            sb.append(" → Forward");
        } else {
            sb.append(" → ").append(dto.getStatus() != null ? dto.getStatus() : 200);
        }
        int condCount = countConditions(dto);
        if (condCount > 0) {
            sb.append(" [").append(condCount).append(condCount == 1 ? " condition" : " conditions").append("]");
        }
        return sb.toString();
    }

    private int countConditions(RuleDto dto) {
        int count = 0;
        if (dto.getBodyCondition() != null && !dto.getBodyCondition().isBlank()) {
            count += dto.getBodyCondition().split(";").length;
        }
        if (dto.getQueryCondition() != null && !dto.getQueryCondition().isBlank()) {
            count += dto.getQueryCondition().split(";").length;
        }
        if (dto.getHeaderCondition() != null && !dto.getHeaderCondition().isBlank()) {
            count += dto.getHeaderCondition().split(";").length;
        }
        return count;
    }

    @Override
    public Map<String, Object> testRule(BaseRule rule, Map<String, Object> request) {
        HttpRule httpRule = (HttpRule) rule;
        long start = System.currentTimeMillis();
        try {
            String query = (String) request.getOrDefault("query", "");
            String body = (String) request.getOrDefault("body", "");
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) request.getOrDefault("headers", Map.of());
            int serverPort = (int) request.getOrDefault("serverPort", 8080);
            
            String path = httpRule.getMatchKey().equals("*") ? "/test" : httpRule.getMatchKey();
            String url = "http://localhost:" + serverPort + "/mock" + path + (query.isEmpty() ? "" : "?" + query);
            
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders reqHeaders = new HttpHeaders();
            headers.forEach(reqHeaders::set);
            reqHeaders.set("X-Original-Host", httpRule.getTargetHost());
            
            HttpMethod httpMethod = HttpMethod.valueOf(httpRule.getMethod());
            HttpEntity<String> entity = new HttpEntity<>(body.isEmpty() ? null : body, reqHeaders);
            
            var response = restTemplate.exchange(url, httpMethod, entity, String.class);
            long elapsed = System.currentTimeMillis() - start;
            
            return Map.of(
                "status", response.getStatusCode().value(),
                "body", response.getBody() != null ? response.getBody() : "",
                "elapsed", elapsed
            );
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return Map.of("status", 500, "body", "Error: " + e.getMessage(), "elapsed", elapsed);
        }
    }
}
