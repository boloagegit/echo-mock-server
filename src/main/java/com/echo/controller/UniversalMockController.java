package com.echo.controller;

import com.echo.entity.FaultType;
import com.echo.entity.HttpRule;
import com.echo.entity.Protocol;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import com.echo.service.HttpRuleService;
import com.echo.service.MatchDescriptionBuilder;
import com.echo.service.MatchResult;
import com.echo.service.RuleService;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 萬用 HTTP Mock 控制器
 * <p>
 * 攔截所有 /mock/** 路徑的請求，根據規則回傳模擬回應：
 * <ol>
 *   <li>從 X-Original-Host 標頭取得目標主機</li>
 *   <li>依據 host + path + method 查詢匹配規則</li>
 *   <li>依據 body/query 條件進行精確匹配</li>
 *   <li>套用延遲後回傳模擬回應</li>
 * </ol>
 * 
 * <h3>使用方式</h3>
 * 將原本的 API 請求改為：
 * <pre>
 * 原本：GET https://api.example.com/users
 * 改為：GET http://localhost:8080/mock/users
 *       Header: X-Original-Host: api.example.com
 * </pre>
 * 
 * @see RuleService 規則匹配邏輯
 */
@RestController
@RequestMapping("/mock")
@Slf4j
public class UniversalMockController {

    /** 原始主機標頭名稱 */
    private static final String ORIGINAL_HOST_HEADER = "X-Original-Host";
    /** 預設主機（當未提供 X-Original-Host 時使用） */
    private static final String DEFAULT_HOST = "default";
    /** 請求逾時時間（毫秒） */
    private static final long REQUEST_TIMEOUT_MS = 30_000L;
    /** 延遲執行緒池大小 */
    private static final int DELAY_THREAD_POOL_SIZE = 8;

    private final RuleService ruleService;
    private final HttpRuleService httpRuleService;
    private final RequestLogService requestLogService;
    private final ResponseTemplateService templateService;
    private final HttpMockPipeline httpMockPipeline;
    
    /** 延遲回應排程器 */
    private final ScheduledExecutorService delayScheduler = 
        Executors.newScheduledThreadPool(DELAY_THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "delay-scheduler");
            t.setDaemon(true);
            return t;
        });

    public UniversalMockController(RuleService ruleService,
                                   HttpRuleService httpRuleService,
                                   RequestLogService requestLogService,
                                   ResponseTemplateService templateService,
                                   HttpMockPipeline httpMockPipeline) {
        this.ruleService = ruleService;
        this.httpRuleService = httpRuleService;
        this.requestLogService = requestLogService;
        this.templateService = templateService;
        this.httpMockPipeline = httpMockPipeline;
    }
    
    @PreDestroy
    public void shutdown() {
        delayScheduler.shutdown();
    }

    /** SSE 逾時時間（毫秒） */
    private static final long SSE_TIMEOUT_MS = 30_000L;
    /** SSE 循環模式逾時時間（24 小時） */
    private static final long SSE_LOOP_TIMEOUT_MS = 86_400_000L;

    /**
     * 處理 SSE 請求（Accept: text/event-stream）。
     * <p>
     * 若匹配到 sseEnabled=true 的規則，回傳 SseEmitter；
     * 若匹配到非 SSE 規則，建構一般 ResponseEntity 回傳；
     * 若無匹配規則，回傳 404。
     */
    @GetMapping(value = "/**", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object handleSseRequest(HttpServletRequest request,
                                   HttpServletResponse response) {
        long startTime = System.currentTimeMillis();

        String originalHost = getOriginalHost(request);
        String rawPath = request.getRequestURI().replaceFirst("^/mock", "");
        final String path = rawPath.isEmpty() ? "/" : rawPath;
        String method = request.getMethod();
        String queryString = request.getQueryString();
        String clientIp = request.getRemoteAddr();

        // 收集 request headers
        Map<String, String> requestHeaders = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            requestHeaders.put(name, request.getHeader(name));
        }

        log.info("SSE request: host={}, path={}, method={}", originalHost, path, method);

        MatchResult<HttpRule> matchResult = httpRuleService.findMatchingHttpRuleWithCandidates(
                originalHost, path, method, null, queryString, requestHeaders);
        long matchTime = System.currentTimeMillis() - startTime;
        String matchChainJson = MatchDescriptionBuilder.toMatchChainJson(matchResult.getMatchChain(), matchResult.isMatched());

        if (!matchResult.isMatched()) {
            long responseTime = System.currentTimeMillis() - startTime;
            requestLogService.record(null, Protocol.HTTP, method, path, false,
                    (int) responseTime, clientIp, matchChainJson, null, null, null, 404, (int) matchTime,
                    null, null);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("No mock rule found for SSE request.");
        }

        HttpRule rule = matchResult.getMatchedRule();

        // Fault injection check
        FaultType faultType = rule.getFaultType() != null ? rule.getFaultType() : FaultType.NONE;
        if (faultType == FaultType.CONNECTION_RESET) {
            long responseTime = System.currentTimeMillis() - startTime;
            requestLogService.record(rule.getId(), Protocol.HTTP, method, path, true,
                    (int) responseTime, clientIp, matchChainJson, originalHost, null, null, null, (int) matchTime,
                    null, null);
            try {
                response.getOutputStream().close();
            } catch (Exception e) {
                log.warn("SSE connection reset: {}", e.getMessage());
            }
            return ResponseEntity.ok().build();
        }
        if (faultType == FaultType.EMPTY_RESPONSE) {
            int faultStatus = rule.getHttpStatus() != null ? rule.getHttpStatus() : 200;
            long responseTime = System.currentTimeMillis() - startTime;
            requestLogService.record(rule.getId(), Protocol.HTTP, method, path, true,
                    (int) responseTime, clientIp, matchChainJson, originalHost, null, null, faultStatus, (int) matchTime,
                    null, "");
            response.setStatus(faultStatus);
            try {
                response.getOutputStream().flush();
            } catch (Exception e) {
                log.warn("SSE empty response flush failed: {}", e.getMessage());
            }
            return ResponseEntity.status(faultStatus).body("");
        }

        // 非 SSE 規則 → fallback 到一般回應
        if (!Boolean.TRUE.equals(rule.getSseEnabled())) {
            String responseBody = rule.getResponseId() != null
                    ? ruleService.findResponseBodyById(rule.getResponseId()).orElse("")
                    : "";

            if (templateService.hasTemplate(responseBody)) {
                Map<String, String> queryParams = parseQueryString(queryString);
                var templateContext = new ResponseTemplateService.TemplateContext(
                        path, method, queryParams, requestHeaders, null);
                responseBody = templateService.render(responseBody, templateContext);
            }

            int status = rule.getHttpStatus() != null ? rule.getHttpStatus() : 200;
            long responseTime = System.currentTimeMillis() - startTime;
            requestLogService.record(rule.getId(), Protocol.HTTP, method, path,
                    true, (int) responseTime, clientIp, matchChainJson, originalHost, null, null, status, (int) matchTime,
                    null, responseBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(detectContentType(responseBody));
            if (rule.getHttpHeaders() != null && !rule.getHttpHeaders().isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, String> customHeaders = new ObjectMapper()
                            .readValue(rule.getHttpHeaders(), Map.class);
                    customHeaders.forEach(headers::add);
                } catch (Exception e) {
                    log.warn("Failed to parse httpHeaders: {}", e.getMessage());
                }
            }
            return ResponseEntity.status(status).headers(headers).body(responseBody);
        }

        // SSE 處理流程
        // responseId 為 null → HTTP 500
        if (rule.getResponseId() == null) {
            long responseTime = System.currentTimeMillis() - startTime;
            requestLogService.record(rule.getId(), Protocol.HTTP, method, path,
                    true, (int) responseTime, clientIp, matchChainJson, originalHost, null, null, 500, (int) matchTime,
                    null, null);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("SSE rule has no response body configured (responseId is null).");
        }

        String responseBody = ruleService.findResponseBodyById(rule.getResponseId()).orElse("");
        List<SseEvent> events = parseSseEvents(responseBody);

        // 事件列表為空 → HTTP 500
        if (events.isEmpty()) {
            long responseTime = System.currentTimeMillis() - startTime;
            requestLogService.record(rule.getId(), Protocol.HTTP, method, path,
                    true, (int) responseTime, clientIp, matchChainJson, originalHost, null, null, 500, (int) matchTime,
                    null, null);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("SSE rule has no valid events.");
        }

        // 記錄請求日誌
        long responseTime = System.currentTimeMillis() - startTime;
        requestLogService.record(rule.getId(), Protocol.HTTP, method, path,
                true, (int) responseTime, clientIp, matchChainJson, originalHost, null, null, 200, (int) matchTime,
                null, responseBody);

        boolean loopEnabled = Boolean.TRUE.equals(rule.getSseLoopEnabled());
        long timeout = loopEnabled ? SSE_LOOP_TIMEOUT_MS : SSE_TIMEOUT_MS;
        SseEmitter emitter = new SseEmitter(timeout);

        // 背景執行緒發送事件
        delayScheduler.execute(() -> sendSseEvents(emitter, events, loopEnabled,
                queryString, requestHeaders, path, method));

        return emitter;
    }

    /**
     * 發送 SSE 事件序列，支援 error/abort/loop 行為。
     * <p>
     * type=normal（或 null）：正常發送事件<br>
     * type=error：發送 error event（name="error"）後 completeWithError<br>
     * type=abort：不送事件，直接 completeWithError<br>
     * loopEnabled=true：事件列表發完後從頭重複，直到客戶端斷開或遇到 error/abort
     */
    void sendSseEvents(SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
                       String queryString, Map<String, String> requestHeaders,
                       String path, String method) {
        try {
            do {
                for (SseEvent event : events) {
                    // 事件間延遲
                    if (event.delayMs() != null && event.delayMs() > 0) {
                        Thread.sleep(event.delayMs());
                    }

                    // 模板渲染
                    String data = event.data();
                    if (templateService.hasTemplate(data)) {
                        Map<String, String> queryParams = parseQueryString(queryString);
                        var templateContext = new ResponseTemplateService.TemplateContext(
                                path, method, queryParams, requestHeaders, null);
                        try {
                            data = templateService.render(data, templateContext);
                        } catch (Exception e) {
                            log.warn("SSE template rendering failed, using raw data: {}", e.getMessage());
                        }
                    }

                    String effectiveType = (event.type() == null) ? "normal" : event.type();

                    switch (effectiveType) {
                        case "error" -> {
                            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                                    .name("error").data(data);
                            if (event.id() != null) {
                                builder.id(event.id());
                            }
                            emitter.send(builder);
                            emitter.completeWithError(new RuntimeException("SSE error event"));
                            return;
                        }
                        case "abort" -> {
                            emitter.completeWithError(new RuntimeException("SSE abort"));
                            return;
                        }
                        default -> {
                            SseEmitter.SseEventBuilder builder = SseEmitter.event().data(data);
                            if (event.event() != null) {
                                builder.name(event.event());
                            }
                            if (event.id() != null) {
                                builder.id(event.id());
                            }
                            emitter.send(builder);
                        }
                    }
                }
            } while (loopEnabled);
            emitter.complete();
        } catch (IOException e) {
            log.warn("SSE client disconnected: {}", e.getMessage());
        } catch (Exception e) {
            log.error("SSE event send error: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /**
     * 處理一般 HTTP Mock 請求，委派給 {@link HttpMockPipeline} 執行 pipeline。
     * <p>
     * Controller 僅負責：
     * <ol>
     *   <li>解析 HttpServletRequest 為 MockRequest</li>
     *   <li>呼叫 pipeline.execute()</li>
     *   <li>將 PipelineResult 轉換為 ResponseEntity</li>
     *   <li>使用 delayScheduler 排程延遲回應</li>
     * </ol>
     */
    @RequestMapping("/**")
    public DeferredResult<ResponseEntity<String>> handleRequest(HttpServletRequest request,
                                                 HttpServletResponse httpResponse,
                                                 @RequestBody(required = false) String body) {
        DeferredResult<ResponseEntity<String>> deferredResult = new DeferredResult<>(REQUEST_TIMEOUT_MS);
        deferredResult.onTimeout(() -> deferredResult.setErrorResult(
                ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body("Request timeout")));
        
        // 1. 解析 HttpServletRequest 為 MockRequest
        String originalHost = getOriginalHost(request);
        String rawPath = request.getRequestURI().replaceFirst("^/mock", "");
        final String path = rawPath.isEmpty() ? "/" : rawPath;
        String method = request.getMethod();
        String queryString = request.getQueryString();
        String clientIp = request.getRemoteAddr();
        
        Map<String, String> requestHeaders = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            requestHeaders.put(name, request.getHeader(name));
        }

        log.info("Mock request: host={}, path={}, method={}", originalHost, path, method);

        MockRequest mockRequest = MockRequest.builder()
                .protocol(Protocol.HTTP)
                .method(method)
                .path(path)
                .queryString(queryString)
                .body(body)
                .clientIp(clientIp)
                .targetHost(originalHost)
                .headers(requestHeaders)
                .build();

        // 2. 呼叫 pipeline
        PipelineResult result = httpMockPipeline.execute(mockRequest);

        // Check fault injection
        String faultType = result.getFaultType();
        if ("CONNECTION_RESET".equals(faultType)) {
            // 先等 delay 再關閉連線（模擬處理後斷線）
            long delay = result.getDelayMs();
            Runnable resetConnection = () -> {
                try {
                    httpResponse.getOutputStream().close();
                } catch (Exception e) {
                    log.warn("Failed to reset connection: {}", e.getMessage());
                }
                deferredResult.setResult(null);
            };
            if (delay > 0) {
                delayScheduler.schedule(resetConnection, delay, TimeUnit.MILLISECONDS);
            } else {
                resetConnection.run();
            }
            return deferredResult;
        }
        if ("EMPTY_RESPONSE".equals(faultType)) {
            // 使用 pipeline 回應的 status code（保留規則設定的 httpStatus）
            int status = result.getResponse() != null ? result.getResponse().getStatus() : 200;
            long delay = result.getDelayMs();
            Runnable emptyResponse = () ->
                    deferredResult.setResult(ResponseEntity.status(status).body(""));
            if (delay > 0) {
                delayScheduler.schedule(emptyResponse, delay, TimeUnit.MILLISECONDS);
            } else {
                emptyResponse.run();
            }
            return deferredResult;
        }

        // 3. 將 PipelineResult 轉換為 ResponseEntity 並排程
        MockResponse mockResponse = result.getResponse();
        Runnable completeResponse = () -> {
            ResponseEntity<String> entity = toResponseEntity(mockResponse);
            deferredResult.setResult(entity);
        };

        long delay = result.getDelayMs();
        if (delay > 0) {
            delayScheduler.schedule(completeResponse, delay, TimeUnit.MILLISECONDS);
        } else {
            completeResponse.run();
        }

        return deferredResult;
    }

    /**
     * 將 MockResponse 轉換為 ResponseEntity
     */
    private ResponseEntity<String> toResponseEntity(MockResponse mockResponse) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(detectContentType(mockResponse.getBody()));

        // 套用自訂 response headers
        if (mockResponse.getHeaders() != null) {
            mockResponse.getHeaders().forEach(headers::add);
        }

        return ResponseEntity.status(mockResponse.getStatus())
                .headers(headers)
                .body(mockResponse.getBody());
    }

    private String getOriginalHost(HttpServletRequest request) {
        String host = request.getHeader(ORIGINAL_HOST_HEADER);
        return (host == null || host.isBlank()) ? DEFAULT_HOST : host.trim();
    }

    private MediaType detectContentType(String body) {
        if (body == null) {
            return MediaType.TEXT_PLAIN;
        }
        String t = body.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            return MediaType.APPLICATION_JSON;
        }
        if (t.startsWith("<")) {
            return MediaType.APPLICATION_XML;
        }
        return MediaType.TEXT_PLAIN;
    }

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

    private static final long MAX_DELAY_MS = 30_000L;
    private static final ObjectMapper SSE_OBJECT_MAPPER = new ObjectMapper();

    /** SSE 事件邏輯模型 */
    record SseEvent(String event, String data, String id, Long delayMs, String type) {}

    /**
     * 解析 Response.body 中的 SSE 事件 JSON 陣列。
     * <p>
     * null/空白/非 JSON 輸入回傳空列表（不拋例外）。
     * 跳過 data 為 null 或空字串的事件。
     * delayMs 為負數視為 0，超過 30000 截斷至 30000。
     */
    List<SseEvent> parseSseEvents(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return List.of();
        }

        String trimmed = jsonStr.trim();
        if (!trimmed.startsWith("[")) {
            return List.of();
        }

        List<SseEvent> raw;
        try {
            raw = SSE_OBJECT_MAPPER.readValue(trimmed, new TypeReference<List<SseEvent>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse SSE events JSON: {}", e.getMessage());
            return List.of();
        }

        List<SseEvent> result = new ArrayList<>();
        for (SseEvent evt : raw) {
            if (evt.data() == null || evt.data().isEmpty()) {
                continue;
            }
            Long delay = evt.delayMs();
            if (delay != null) {
                if (delay < 0) {
                    delay = 0L;
                } else if (delay > MAX_DELAY_MS) {
                    delay = MAX_DELAY_MS;
                }
            }
            result.add(new SseEvent(evt.event(), evt.data(), evt.id(), delay, evt.type()));
        }
        return result;
    }

}
