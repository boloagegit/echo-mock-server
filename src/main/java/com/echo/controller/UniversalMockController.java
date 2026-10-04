package com.echo.controller;

import com.echo.entity.HttpRule;
import com.echo.entity.FaultType;
import com.echo.entity.Protocol;
import com.echo.pipeline.AbstractMockPipeline;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import com.echo.service.HttpRuleService;
import com.echo.service.ConditionMatcher;
import com.echo.service.MatchDescriptionBuilder;
import com.echo.service.MatchResult;
import com.echo.service.RuleService;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import com.echo.service.SseEventSequence;
import com.echo.service.SseRuleSettings;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.ServerConnection;
import io.undertow.servlet.handlers.ServletRequestContext;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    /** 預設高於 pool 3 s + connect 5 s + response 30 s 的最壞情況。 */
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 40_000L;
    /** 延遲執行緒池大小 */
    private static final int DELAY_THREAD_POOL_SIZE = 8;

    private final RuleService ruleService;
    private final HttpRuleService httpRuleService;
    private final RequestLogService requestLogService;
    private final ResponseTemplateService templateService;
    private final HttpMockPipeline httpMockPipeline;

    @Value("${echo.http.request-timeout-ms:40000}")
    private long requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
    
    /** 延遲回應排程器 */
    private final ScheduledThreadPoolExecutor delayScheduler = createDelayScheduler("delay-scheduler");
    // Blocking servlet writes must not consume the general HTTP delay workers.
    private final ScheduledExecutorService sseScheduler;
    private final Set<SsePlayback> ssePlaybacks = ConcurrentHashMap.newKeySet();

    private static ScheduledThreadPoolExecutor createDelayScheduler(String threadName) {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(DELAY_THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Autowired
    public UniversalMockController(RuleService ruleService,
                                   HttpRuleService httpRuleService,
                                   RequestLogService requestLogService,
                                   ResponseTemplateService templateService,
                                   HttpMockPipeline httpMockPipeline) {
        this(ruleService, httpRuleService, requestLogService, templateService, httpMockPipeline,
                createDelayScheduler("sse-scheduler"));
    }

    UniversalMockController(RuleService ruleService, HttpRuleService httpRuleService,
                            RequestLogService requestLogService, ResponseTemplateService templateService,
                            HttpMockPipeline httpMockPipeline, ScheduledExecutorService sseScheduler) {
        this.sseScheduler = sseScheduler != null ? sseScheduler : createDelayScheduler("sse-scheduler");
        this.ruleService = ruleService;
        this.httpRuleService = httpRuleService;
        this.requestLogService = requestLogService;
        this.templateService = templateService;
        this.httpMockPipeline = httpMockPipeline;
    }
    
    @PreDestroy
    public void shutdown() {
        ssePlaybacks.forEach(SsePlayback::stop);
        sseScheduler.shutdownNow();
        delayScheduler.shutdown();
    }

    public int getPendingDelayTaskCount() {
        return delayScheduler.getQueue().size();
    }

    public int getActiveDelayWorkerCount() { return delayScheduler.getActiveCount(); }

    public int getDelayWorkerCapacity() { return delayScheduler.getCorePoolSize(); }

    public int getPendingSseTaskCount() {
        return sseScheduler instanceof ScheduledThreadPoolExecutor executor ? executor.getQueue().size() : 0;
    }

    /**
     * 處理 SSE 請求（Accept: text/event-stream）。
     * <p>
     * 若匹配到 sseEnabled=true 的規則，回傳 SseEmitter；
     * 若匹配到非 SSE 規則，建構一般 ResponseEntity 回傳；
     * 若無匹配規則，回傳 404。
     */
    public Object handleSseRequest(HttpServletRequest request, HttpServletResponse response) {
        return handleSseRequest(request, response, null);
    }

    @RequestMapping(value = "/**", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object handleSseRequest(HttpServletRequest request, HttpServletResponse response,
                                   @RequestBody(required = false) String body) {
        // Produces conditions also match */*. Keep ordinary requests on their original pipeline.
        if (httpMockPipeline != null && !explicitlyAcceptsSse(request)) {
            return handleRequest(request, response, body);
        }
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

        log.debug("SSE request: host={}, path={}, method={}", originalHost, path, method);

        MatchResult<HttpRule> matchResult;
        if (httpMockPipeline != null) {
            List<HttpRule> candidates = httpRuleService.findPreparedHttpRules(originalHost, path, method);
            matchResult = httpMockPipeline.matchRule(candidates,
                    body == null ? ConditionMatcher.PreparedBody.rawOnly(null)
                            : httpMockPipeline.prepareBodyForMatching(MockRequest.builder().body(body).build(), candidates),
                    queryString, requestHeaders);
        } else {
            // 保留給舊有單元測試與嵌入式呼叫端的相容路徑。
            matchResult = httpRuleService.findMatchingHttpRuleWithCandidates(
                    originalHost, path, method, body, queryString, requestHeaders);
        }
        long matchTime = System.currentTimeMillis() - startTime;
        String matchChainJson = MatchDescriptionBuilder.toMatchChainJson(matchResult.getMatchChain(), matchResult.isMatched());

        if (!matchResult.isMatched()) {
            long responseTime = System.currentTimeMillis() - startTime;
            requestLogService.record(null, Protocol.HTTP, method, path, false,
                    (int) responseTime, clientIp, matchChainJson, null, null, null, 404, (int) matchTime,
                    null, null);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("No mock rule found for SSE request.");
        }

        HttpRule rule = matchResult.getMatchedRule();
        int configuredStatus = rule.getHttpStatus() == null ? 200 : rule.getHttpStatus();
        if (Boolean.TRUE.equals(rule.getSseEnabled())
                && ("HEAD".equals(method) || "OPTIONS".equals(method))) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .header(HttpHeaders.ALLOW, "GET, POST, PUT, PATCH, DELETE")
                    .contentType(MediaType.TEXT_PLAIN).body("HTTP method does not support SSE playback");
        }
        if (httpMockPipeline != null && (!Boolean.TRUE.equals(rule.getSseEnabled()) || configuredStatus != 200)) {
            return handleRequest(request, response, body);
        }
        AbstractMockPipeline.ScenarioTransition scenarioTransition = httpMockPipeline != null
                ? httpMockPipeline.advanceScenarioState(rule)
                : null;

        // Fault injection check
        FaultType faultType = rule.getFaultType() != null ? rule.getFaultType() : FaultType.NONE;
        if (faultType == FaultType.CONNECTION_RESET) {
            long responseTime = System.currentTimeMillis() - startTime;
            recordSseRule(rule, method, path, (int) responseTime, clientIp,
                    matchChainJson, originalHost, null, (int) matchTime, null,
                    queryString, requestHeaders, faultType, scenarioTransition, body);
            captureConnectionReset(response).run();
            return ResponseEntity.ok().build();
        }
        if (faultType == FaultType.EMPTY_RESPONSE) {
            int faultStatus = rule.getHttpStatus() != null ? rule.getHttpStatus() : 200;
            long responseTime = System.currentTimeMillis() - startTime;
            recordSseRule(rule, method, path, (int) responseTime, clientIp,
                    matchChainJson, originalHost, faultStatus, (int) matchTime, "",
                    queryString, requestHeaders, faultType, scenarioTransition, body);
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
                        path, method, queryParams, requestHeaders, body);
                responseBody = templateService.render(responseBody, templateContext);
            }

            int status = rule.getHttpStatus() != null ? rule.getHttpStatus() : 200;
            long responseTime = System.currentTimeMillis() - startTime;
            recordSseRule(rule, method, path, (int) responseTime, clientIp,
                    matchChainJson, originalHost, status, (int) matchTime, responseBody,
                    queryString, requestHeaders, faultType, scenarioTransition, body);

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
            recordSseRule(rule, method, path, (int) responseTime, clientIp,
                    matchChainJson, originalHost, 500, (int) matchTime, null,
                    queryString, requestHeaders, faultType, scenarioTransition, body);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("SSE rule has no response body configured (responseId is null).");
        }

        String responseBody = ruleService.findResponseBodyById(rule.getResponseId()).orElse("");
        List<SseEvent> events = parseSseEvents(responseBody);

        // 事件列表為空 → HTTP 500
        if (events.isEmpty()) {
            long responseTime = System.currentTimeMillis() - startTime;
            recordSseRule(rule, method, path, (int) responseTime, clientIp,
                    matchChainJson, originalHost, 500, (int) matchTime, null,
                    queryString, requestHeaders, faultType, scenarioTransition, body);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("SSE rule has no valid events.");
        }

        boolean loopEnabled = Boolean.TRUE.equals(rule.getSseLoopEnabled());
        long initialDelay;
        HttpHeaders headers;
        long timeout;
        try {
            initialDelay = AbstractMockPipeline.calculateDelay(rule.getDelayMs() == null ? 0 : rule.getDelayMs(), rule.getMaxDelayMs());
            headers = SseRuleSettings.headers(rule.getHttpHeaders(), configuredStatus);
            timeout = SseRuleSettings.timeout(SseEventSequence.parse(responseBody), initialDelay, loopEnabled);
        } catch (IllegalArgumentException error) {
            recordSseRule(rule, method, path, (int) (System.currentTimeMillis() - startTime), clientIp,
                    matchChainJson, originalHost, 500, (int) matchTime, null,
                    queryString, requestHeaders, faultType, scenarioTransition, body);
            return ResponseEntity.internalServerError().contentType(MediaType.TEXT_PLAIN).body(error.getMessage());
        }
        recordSseRule(rule, method, path, (int) (System.currentTimeMillis() - startTime), clientIp,
                matchChainJson, originalHost, configuredStatus, (int) matchTime, responseBody,
                queryString, requestHeaders, faultType, scenarioTransition, body);
        if (configuredStatus != 200) {
            return ResponseEntity.status(configuredStatus).headers(headers).body(responseBody);
        }
        if (response != null) headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        SseEmitter emitter = new SseEmitter(timeout);

        // 每個事件只在實際發送時短暫使用 scheduler；等待期間不占用執行緒。
        createSsePlayback(emitter, events, loopEnabled,
                queryString, requestHeaders, path, method, body, initialDelay).start();

        return emitter;
    }

    private static boolean explicitlyAcceptsSse(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HttpHeaders.ACCEPT);
        while (values.hasMoreElements()) {
            for (MediaType type : MediaType.parseMediaTypes(values.nextElement())) {
                if ("text".equalsIgnoreCase(type.getType()) && "event-stream".equalsIgnoreCase(type.getSubtype())
                        && type.getQualityValue() > 0) return true;
            }
        }
        return false;
    }

    private void recordSseRule(
            HttpRule rule, String method, String path, int responseTimeMs,
            String clientIp, String matchChainJson, String originalHost,
            Integer responseStatus, int matchTimeMs, String responseBody,
            String queryString, Map<String, String> requestHeaders,
            FaultType faultType, AbstractMockPipeline.ScenarioTransition scenarioTransition, String body) {
        if (faultType == FaultType.NONE && scenarioTransition == null) {
            requestLogService.record(rule.getId(), Protocol.HTTP, method, path, true,
                    responseTimeMs, clientIp, matchChainJson, originalHost,
                    null, null, responseStatus, matchTimeMs, body, responseBody);
            return;
        }
        requestLogService.record(rule.getId(), Protocol.HTTP, method, path, true,
                responseTimeMs, clientIp, matchChainJson, originalHost,
                null, null, responseStatus, matchTimeMs, body, responseBody,
                List.of(rule), ConditionMatcher.PreparedBody.rawOnly(body),
                queryString, requestHeaders,
                faultType != FaultType.NONE ? faultType.name() : null,
                scenarioTransition != null ? scenarioTransition.name() : null,
                scenarioTransition != null ? scenarioTransition.fromState() : null,
                scenarioTransition != null ? scenarioTransition.toState() : null);
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
        startSsePlayback(emitter, events, loopEnabled, queryString, requestHeaders, path, method).join();
    }

    CompletableFuture<Void> startSsePlayback(
            SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
            String queryString, Map<String, String> requestHeaders,
            String path, String method) {
        SsePlayback playback = createSsePlayback(emitter, events, loopEnabled,
                queryString, requestHeaders, path, method);
        playback.start();
        return playback.completion();
    }

    private SsePlayback createSsePlayback(
            SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
            String queryString, Map<String, String> requestHeaders,
            String path, String method) {
        return createSsePlayback(emitter, events, loopEnabled, queryString, requestHeaders, path, method, null, 0);
    }

    private SsePlayback createSsePlayback(SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
            String queryString, Map<String, String> requestHeaders, String path, String method,
            String body, long initialDelay) {
        return new SsePlayback(emitter, events, loopEnabled,
                parseQueryString(queryString), requestHeaders, path, method, body, initialDelay);
    }

    /** Non-blocking event state machine: delays are scheduler timestamps, not Thread.sleep. */
    private final class SsePlayback {
        private final SseEmitter emitter;
        private final List<SseEvent> events;
        private final boolean loopEnabled;
        private final Map<String, String> queryParams;
        private final Map<String, String> requestHeaders;
        private final String path;
        private final String method;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicReference<PendingSseTask> scheduled = new AtomicReference<>();
        private final String body;
        private long initialDelay;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private int index;

        private SsePlayback(SseEmitter emitter, List<SseEvent> events, boolean loopEnabled,
                            Map<String, String> queryParams, Map<String, String> requestHeaders,
                            String path, String method, String body, long initialDelay) {
            this.body = body;
            this.initialDelay = initialDelay;
            this.emitter = emitter;
            this.events = events;
            this.loopEnabled = loopEnabled;
            this.queryParams = queryParams;
            this.requestHeaders = requestHeaders;
            this.path = path;
            this.method = method;
        }

        private void start() {
            ssePlaybacks.add(this);
            emitter.onCompletion(this::stop);
            emitter.onTimeout(this::stop);
            emitter.onError(ignored -> stop());
            scheduleNext();
        }

        private CompletableFuture<Void> completion() {
            return completion;
        }

        private void scheduleNext() {
            if (stopped.get()) return;
            SseEvent event = events.get(index);
            long delayMs = (event.delayMs() == null ? 0 : Math.max(0, event.delayMs())) + initialDelay;
            initialDelay = 0;
            PendingSseTask pending = new PendingSseTask();
            // Publish ownership before schedule(): a zero-delay task can run before it returns.
            if (!scheduled.compareAndSet(null, pending)) return;
            if (stopped.get()) {
                cancelPending();
                return;
            }
            try {
                pending.attach(sseScheduler.schedule(() -> {
                    if (scheduled.compareAndSet(pending, null)) deliver(event);
                }, delayMs, TimeUnit.MILLISECONDS));
            } catch (RuntimeException error) {
                fail(error, "SSE scheduling failed");
            }
        }

        private void deliver(SseEvent event) {
            if (stopped.get()) return;
            try {
                String data = render(event.data());
                String effectiveType = event.type() == null ? "normal" : event.type();
                switch (effectiveType) {
                    case "error" -> {
                        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                                .name("error").data(wireData(data), new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8));
                        if (event.id() != null) builder.id(event.id());
                        emitter.send(builder);
                        fail(new RuntimeException("SSE error event"), null);
                    }
                    case "abort" -> fail(new RuntimeException("SSE abort"), null);
                    default -> {
                        SseEmitter.SseEventBuilder builder = SseEmitter.event().data(wireData(data), new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8));
                        if (event.event() != null) builder.name(event.event());
                        if (event.id() != null) builder.id(event.id());
                        emitter.send(builder);
                        advance();
                    }
                }
            } catch (IOException error) {
                log.warn("SSE client disconnected: {}", error.getMessage());
                stop();
            } catch (Exception error) {
                fail(error, "SSE event send error");
            }
        }

        private String render(String data) {
            if (!templateService.hasTemplate(data)) return data;
            var context = new ResponseTemplateService.TemplateContext(
                    path, method, queryParams, requestHeaders, body);
            try {
                return templateService.render(data, context);
            } catch (Exception error) {
                log.warn("SSE template rendering failed, using raw data: {}", error.getMessage());
                return data;
            }
        }

        private void advance() {
            index++;
            if (index < events.size()) {
                scheduleNext();
            } else if (loopEnabled) {
                index = 0;
                scheduleNext();
            } else {
                complete();
            }
        }

        private void complete() {
            if (!stopped.compareAndSet(false, true)) return;
            cancelPending();
            ssePlaybacks.remove(this);
            emitter.complete();
            completion.complete(null);
        }

        private void fail(Exception error, String message) {
            if (!stopped.compareAndSet(false, true)) return;
            cancelPending();
            ssePlaybacks.remove(this);
            if (message != null) log.error("{}: {}", message, error.getMessage());
            emitter.completeWithError(error);
            completion.complete(null);
        }

        private void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            cancelPending();
            ssePlaybacks.remove(this);
            completion.complete(null);
        }

        private void cancelPending() {
            PendingSseTask task = scheduled.getAndSet(null);
            if (task != null) task.cancel();
        }
    }

    private static String wireData(String data) {
        return data.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static final class PendingSseTask {
        private ScheduledFuture<?> future;
        private boolean cancelled;

        synchronized void attach(ScheduledFuture<?> task) {
            future = task;
            if (cancelled) task.cancel(false);
        }

        synchronized void cancel() {
            cancelled = true;
            if (future != null) future.cancel(false);
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
    @SuppressWarnings("FutureReturnValueIgnored")
    public Object handleRequest(HttpServletRequest request,
                                HttpServletResponse httpResponse,
                                @RequestBody(required = false) String body) {
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

        log.debug("Mock request: host={}, path={}, method={}", originalHost, path, method);

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

        // 2. Mock 直接完成；實際下游 I/O 使用非阻塞 client。
        CompletableFuture<PipelineResult> pipelineFuture =
                httpMockPipeline.executeAsync(mockRequest).toCompletableFuture();
        Runnable connectionReset = captureConnectionReset(httpResponse);

        // 一般 Mock／404 已在目前執行緒完成，直接回傳可省下 MVC async context、
        // DeferredResult 與 callback 配置；轉發或延遲回應仍保留原非同步流程。
        if (pipelineFuture.isDone()) {
            try {
                PipelineResult immediate = pipelineFuture.join();
                if (immediate.getDelayMs() <= 0) {
                    if ("CONNECTION_RESET".equals(immediate.getFaultType())) {
                        connectionReset.run();
                        return null;
                    }
                    return toResponseEntity(immediate.getResponse());
                }
            } catch (RuntimeException error) {
                log.error("HTTP pipeline error: {}", error.getMessage(), error);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Pipeline error");
            }
        }

        return deferResponse(pipelineFuture, connectionReset);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private DeferredResult<ResponseEntity<String>> deferResponse(
            CompletableFuture<PipelineResult> pipelineFuture,
            Runnable connectionReset) {
        DeferredResult<ResponseEntity<String>> deferredResult = new DeferredResult<>(requestTimeoutMs);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> delayedTask = new AtomicReference<>();
        deferredResult.onTimeout(() -> {
            if (terminal.compareAndSet(false, true)) {
                cancelScheduled(delayedTask);
                pipelineFuture.cancel(true);
                deferredResult.setErrorResult(
                        ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body("Request timeout"));
            }
        });
        deferredResult.onError(ignored -> terminateDeferred(terminal, delayedTask, pipelineFuture));
        deferredResult.onCompletion(() -> {
            terminateDeferred(terminal, delayedTask, pipelineFuture);
        });
        pipelineFuture.whenComplete((result, error) -> {
            if (terminal.get() || deferredResult.isSetOrExpired()) return;
            if (error != null) {
                log.error("Async HTTP pipeline error: {}", error.getMessage(), error);
                if (terminal.compareAndSet(false, true)) {
                    deferredResult.setErrorResult(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Pipeline error"));
                }
                return;
            }
            completeResponse(deferredResult, result, connectionReset, terminal, delayedTask);
        });

        return deferredResult;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void completeResponse(DeferredResult<ResponseEntity<String>> deferredResult,
                                  PipelineResult result,
                                  Runnable connectionReset,
                                  AtomicBoolean terminal,
                                  AtomicReference<ScheduledFuture<?>> delayedTask) {
        if (terminal.get() || deferredResult.isSetOrExpired()) return;
        MockResponse mockResponse = result.getResponse();
        Runnable completion = () -> {
            delayedTask.set(null);
            if (!deferredResult.isSetOrExpired() && terminal.compareAndSet(false, true)) {
                if ("CONNECTION_RESET".equals(result.getFaultType())) {
                    connectionReset.run();
                    deferredResult.setResult(null);
                } else {
                    deferredResult.setResult(toResponseEntity(mockResponse));
                }
            }
        };
        long delay = result.getDelayMs();
        if (delay > 0) {
            ScheduledFuture<?> task = delayScheduler.schedule(completion, delay, TimeUnit.MILLISECONDS);
            if (!delayedTask.compareAndSet(null, task)) {
                task.cancel(false);
                return;
            }
            // Timeout/completion may win between scheduling and publishing the future.
            if ((terminal.get() || deferredResult.isSetOrExpired())
                    && delayedTask.compareAndSet(task, null)) {
                task.cancel(false);
            }
        } else {
            completion.run();
        }
    }

    private void terminateDeferred(
            AtomicBoolean terminal,
            AtomicReference<ScheduledFuture<?>> delayedTask,
            CompletableFuture<?> pipelineFuture) {
        terminal.set(true);
        cancelScheduled(delayedTask);
        if (!pipelineFuture.isDone()) pipelineFuture.cancel(true);
    }

    private void cancelScheduled(AtomicReference<ScheduledFuture<?>> scheduled) {
        ScheduledFuture<?> task = scheduled.getAndSet(null);
        if (task != null) task.cancel(false);
    }

    private Runnable captureConnectionReset(HttpServletResponse response) {
        ServletRequestContext requestContext = ServletRequestContext.current();
        if (requestContext != null) {
            ServerConnection connection = requestContext.getExchange().getConnection();
            return () -> closeTransportConnection(connection);
        }
        return () -> closeResponseStream(response);
    }

    private void closeTransportConnection(ServerConnection connection) {
        try {
            connection.close();
        } catch (IOException e) {
            log.warn("Failed to reset transport connection: {}", e.getMessage());
        }
    }

    private void closeResponseStream(HttpServletResponse response) {
        try {
            response.getOutputStream().close();
        } catch (IOException e) {
            log.warn("Failed to close fallback response stream: {}", e.getMessage());
        }
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

    /** SSE event model retained for embedded callers and test seams. */
    record SseEvent(String event, String data, String id, Long delayMs, String type) {}

    /** Invalid persisted sequences fail as a whole using the same contract as rule saves. */
    List<SseEvent> parseSseEvents(String jsonStr) {
        try {
            return SseEventSequence.parse(jsonStr).stream()
                    .map(event -> new SseEvent(event.event(), event.data(), event.id(), event.delayMs(), event.type()))
                    .toList();
        } catch (IllegalArgumentException error) {
            log.warn("Failed to parse SSE events JSON: {}", error.getMessage());
            return List.of();
        }
    }
}
