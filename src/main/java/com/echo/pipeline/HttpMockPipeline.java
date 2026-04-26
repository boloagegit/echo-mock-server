package com.echo.pipeline;

import com.echo.entity.HttpRule;
import com.echo.service.ConditionMatcher;
import com.echo.service.HttpRuleService;
import com.echo.service.MatchChainEntry;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.RuleService;
import com.echo.service.ScenarioService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 協定的 MockPipeline 實作
 * <p>
 * 實作 HTTP 特有的 pipeline 步驟：
 * <ul>
 *   <li>候選規則查詢：委派給 {@link HttpRuleService}</li>
 *   <li>回應建構：模板渲染 + 自訂 HTTP headers</li>
 *   <li>轉發：使用 {@link RestTemplate} proxy 轉發</li>
 * </ul>
 */
@Component
@Slf4j
public class HttpMockPipeline extends AbstractMockPipeline<HttpRule> {

    private static final String ORIGINAL_HOST_HEADER = "X-Original-Host";
    private static final String DEFAULT_HOST = "default";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpRuleService httpRuleService;
    private final ResponseTemplateService templateService;
    private final RestTemplate restTemplate;

    public HttpMockPipeline(ConditionMatcher conditionMatcher,
                            RuleService ruleService,
                            RequestLogService requestLogService,
                            HttpRuleService httpRuleService,
                            ResponseTemplateService templateService,
                            RestTemplate restTemplate,
                            ScenarioService scenarioService) {
        super(conditionMatcher, ruleService, requestLogService, scenarioService);
        this.httpRuleService = httpRuleService;
        this.templateService = templateService;
        this.restTemplate = restTemplate;
    }

    @Override
    protected List<HttpRule> findCandidateRules(MockRequest request) {
        return httpRuleService.findPreparedHttpRules(
                request.getTargetHost(), request.getPath(), request.getMethod());
    }

    @Override
    protected MockResponse buildResponse(HttpRule rule, MockRequest request, String responseBody) {
        // 模板渲染（有 {{ 語法才處理）
        String renderedBody = responseBody;
        if (templateService.hasTemplate(responseBody)) {
            Map<String, String> queryParams = parseQueryString(request.getQueryString());
            var templateContext = new ResponseTemplateService.TemplateContext(
                    request.getPath(), request.getMethod(), queryParams,
                    request.getHeaders(), request.getBody());
            renderedBody = templateService.render(responseBody, templateContext);
        }

        // 解析自訂 HTTP headers
        Map<String, String> responseHeaders = parseHttpHeaders(rule.getHttpHeaders());

        int status = rule.getHttpStatus() != null ? rule.getHttpStatus() : 200;

        return MockResponse.builder()
                .status(status)
                .body(renderedBody)
                .headers(responseHeaders)
                .matched(true)
                .forwarded(false)
                .build();
    }

    @Override
    protected MockResponse forward(MockRequest request) {
        String targetHost = request.getTargetHost();
        String url = "https://" + targetHost + request.getPath();
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            url += "?" + request.getQueryString();
        }

        log.info("Proxy forwarding to: {} {}", request.getMethod(), url);

        try {
            HttpHeaders headers = new HttpHeaders();
            if (request.getHeaders() != null) {
                request.getHeaders().forEach((name, value) -> {
                    if (!name.equalsIgnoreCase(ORIGINAL_HOST_HEADER)
                            && !name.equalsIgnoreCase("host")
                            && !name.equalsIgnoreCase("content-length")) {
                        headers.add(name, value);
                    }
                });
            }

            HttpEntity<String> entity = new HttpEntity<>(request.getBody(), headers);
            HttpMethod httpMethod = HttpMethod.valueOf(request.getMethod().toUpperCase(Locale.ROOT));

            ResponseEntity<String> response = restTemplate.exchange(url, httpMethod, entity, String.class);

            log.info("Proxy response: status={}", response.getStatusCode());
            int statusCode = response.getStatusCode().value();

            return MockResponse.builder()
                    .status(statusCode)
                    .body(response.getBody())
                    .matched(false)
                    .forwarded(true)
                    .build();

        } catch (Exception e) {
            log.error("Proxy error: {}", e.getMessage());
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.length() > 255) {
                errorMsg = errorMsg.substring(0, 255);
            }

            return MockResponse.builder()
                    .status(502)
                    .body("Proxy error: " + e.getMessage())
                    .matched(false)
                    .forwarded(true)
                    .proxyError(errorMsg)
                    .build();
        }
    }

    @Override
    protected boolean shouldForward(MockRequest request) {
        return request.getTargetHost() != null && !DEFAULT_HOST.equals(request.getTargetHost());
    }

    @Override
    protected MockResponse handleNoMatch(MockRequest request) {
        return MockResponse.builder()
                .status(404)
                .body("No mock rule found for: " + request.getMethod() + " " + request.getPath())
                .matched(false)
                .forwarded(false)
                .build();
    }

    @Override
    protected boolean hasCondition(HttpRule rule) {
        return (rule.getBodyCondition() != null && !rule.getBodyCondition().isBlank())
                || (rule.getQueryCondition() != null && !rule.getQueryCondition().isBlank())
                || (rule.getHeaderCondition() != null && !rule.getHeaderCondition().isBlank());
    }

    @Override
    protected ConditionSet extractConditions(HttpRule rule) {
        return ConditionSet.builder()
                .bodyCondition(rule.getBodyCondition())
                .queryCondition(rule.getQueryCondition())
                .headerCondition(rule.getHeaderCondition())
                .build();
    }

    @Override
    protected MatchChainEntry createMatchChainEntry(HttpRule rule, String reason) {
        return MatchChainEntry.fromHttp(rule, reason);
    }

    // ==================== Private Helpers ====================

    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> params = new HashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return params;
        }
        for (String pair : queryString.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                params.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHttpHeaders(String httpHeadersJson) {
        if (httpHeadersJson == null || httpHeadersJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return OBJECT_MAPPER.readValue(httpHeadersJson, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse httpHeaders: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
