package com.echo.controller;

import com.echo.diagnostics.TransactionDiagnostics;
import com.echo.diagnostics.TransactionDiagnostics.Trace;
import com.echo.entity.Protocol;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import com.echo.protocol.http.sse.SseMockHandler;
import com.echo.protocol.http.sse.SsePlayer;
import com.echo.service.HttpRuleService;
import com.echo.service.RuleService;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
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

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
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

    private final HttpMockPipeline httpMockPipeline;
    private final SseMockHandler sseHandler;
    /** 由相容建構子自行建立的播放器需要在關機時一併停止；Spring bean 由容器管理。 */
    private final boolean ownsSsePlayer;
    private TransactionDiagnostics diagnostics;

    @Autowired
    public void setDiagnostics(TransactionDiagnostics diagnostics) { this.diagnostics = diagnostics; }

    @Value("${echo.http.request-timeout-ms:40000}")
    private long requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
    
    /** 延遲回應排程器 */
    private final ScheduledThreadPoolExecutor delayScheduler = createDelayScheduler("delay-scheduler");

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
    public UniversalMockController(HttpMockPipeline httpMockPipeline, SseMockHandler sseHandler) {
        this(httpMockPipeline, sseHandler, false);
    }

    /** 嵌入式呼叫端與單元測試使用：自行建立 SSE 處理器與播放器。 */
    public UniversalMockController(RuleService ruleService,
                                   HttpRuleService httpRuleService,
                                   RequestLogService requestLogService,
                                   ResponseTemplateService templateService,
                                   HttpMockPipeline httpMockPipeline) {
        this(httpMockPipeline, new SseMockHandler(ruleService, httpRuleService, requestLogService,
                templateService, httpMockPipeline, new SsePlayer(templateService)), true);
    }

    private UniversalMockController(HttpMockPipeline httpMockPipeline, SseMockHandler sseHandler,
                                    boolean ownsSsePlayer) {
        this.httpMockPipeline = httpMockPipeline;
        this.sseHandler = sseHandler;
        this.ownsSsePlayer = ownsSsePlayer;
    }
    
    @PreDestroy
    public void shutdown() {
        if (ownsSsePlayer) {
            sseHandler.player().shutdown();
        }
        delayScheduler.shutdown();
    }

    public int getPendingDelayTaskCount() {
        return delayScheduler.getQueue().size();
    }

    public int getActiveDelayWorkerCount() { return delayScheduler.getActiveCount(); }

    public int getDelayWorkerCapacity() { return delayScheduler.getCorePoolSize(); }

    public int getPendingSseTaskCount() {
        return sseHandler.player().getPendingTaskCount();
    }

    /**
     * 處理 SSE 請求（Accept: text/event-stream），委派給 {@link SseMockHandler}。
     * <p>
     * 若匹配到 sseEnabled=true 的規則，回傳 SseEmitter；
     * 若匹配到非 SSE 規則，建構一般 ResponseEntity 回傳；
     * 若無匹配規則，GET/HEAD 保留 404；其他方法委派一般 HTTP pipeline。
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
        Trace trace = diagnostics == null ? Trace.NONE : diagnostics.begin("SSE");
        try {
            return sseHandler.handle(toMockRequest(request, body, trace), response,
                    captureConnectionReset(response), () -> handleRequest(request, response, body));
        } catch (RuntimeException error) {
            trace.failure("SSE_PREPARATION_FAILED", error);
            trace.end("PREPARATION_FAILED");
            throw error;
        }
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

    private MockRequest toMockRequest(HttpServletRequest request, String body, Trace trace) {
        String rawPath = request.getRequestURI().replaceFirst("^/mock", "");
        Map<String, String> requestHeaders = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            requestHeaders.put(name, request.getHeader(name));
        }
        return MockRequest.builder()
                .protocol(Protocol.HTTP)
                .method(request.getMethod())
                .path(rawPath.isEmpty() ? "/" : rawPath)
                .queryString(request.getQueryString())
                .body(body)
                .clientIp(request.getRemoteAddr())
                .targetHost(getOriginalHost(request))
                .headers(requestHeaders)
                .trace(trace)
                .build();
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
        Trace trace = diagnostics == null ? Trace.NONE : diagnostics.begin("HTTP");
        MockRequest mockRequest = toMockRequest(request, body, trace);
        trace.event("RECEIVED", "method", mockRequest.getMethod(), "path", mockRequest.getPath());
        log.debug("Mock request: host={}, path={}, method={}",
                mockRequest.getTargetHost(), mockRequest.getPath(), mockRequest.getMethod());

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
                        trace.end("FAULT_CONNECTION_RESET");
                        connectionReset.run();
                        return null;
                    }
                    trace.event("HTTP_RESULT_READY", "status", immediate.getResponse().getStatus());
                    trace.end("RESULT_READY_NOT_DELIVERY_CONFIRMATION");
                    return toResponseEntity(immediate.getResponse());
                }
            } catch (RuntimeException error) {
                trace.failure("HTTP_PIPELINE_FAILED", error);
                trace.end("PIPELINE_FAILED");
                log.error("HTTP pipeline error: {}", error.getMessage(), error);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Pipeline error");
            }
        }

        return deferResponse(pipelineFuture, connectionReset, trace);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private DeferredResult<ResponseEntity<String>> deferResponse(
            CompletableFuture<PipelineResult> pipelineFuture,
            Runnable connectionReset, Trace trace) {
        DeferredResult<ResponseEntity<String>> deferredResult = new DeferredResult<>(requestTimeoutMs);
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> delayedTask = new AtomicReference<>();
        deferredResult.onTimeout(() -> {
            if (terminal.compareAndSet(false, true)) {
                trace.end("HTTP_TIMEOUT");
                cancelScheduled(delayedTask);
                pipelineFuture.cancel(true);
                deferredResult.setErrorResult(
                        ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body("Request timeout"));
            }
        });
        deferredResult.onError(error -> {
            trace.failure("HTTP_TRANSPORT_ERROR", error);
            trace.end("HTTP_TRANSPORT_ERROR");
            terminateDeferred(terminal, delayedTask, pipelineFuture);
        });
        deferredResult.onCompletion(() -> {
            trace.end("HTTP_ASYNC_COMPLETED_NOT_DELIVERY_CONFIRMATION");
            terminateDeferred(terminal, delayedTask, pipelineFuture);
        });
        pipelineFuture.whenComplete((result, error) -> {
            if (terminal.get() || deferredResult.isSetOrExpired()) return;
            if (error != null) {
                trace.failure("HTTP_PIPELINE_FAILED", error);
                log.error("Async HTTP pipeline error: {}", error.getMessage(), error);
                if (terminal.compareAndSet(false, true)) {
                    deferredResult.setErrorResult(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Pipeline error"));
                }
                return;
            }
            completeResponse(deferredResult, result, connectionReset, terminal, delayedTask, trace);
        });

        return deferredResult;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void completeResponse(DeferredResult<ResponseEntity<String>> deferredResult,
                                  PipelineResult result,
                                  Runnable connectionReset,
                                  AtomicBoolean terminal,
                                  AtomicReference<ScheduledFuture<?>> delayedTask, Trace trace) {
        if (terminal.get() || deferredResult.isSetOrExpired()) return;
        MockResponse mockResponse = result.getResponse();
        Runnable completion = () -> {
            delayedTask.set(null);
            if (!deferredResult.isSetOrExpired() && terminal.compareAndSet(false, true)) {
                if ("CONNECTION_RESET".equals(result.getFaultType())) {
                    trace.event("HTTP_RESULT_READY", "fault", "CONNECTION_RESET");
                    connectionReset.run();
                    deferredResult.setResult(null);
                } else {
                    trace.event("HTTP_RESULT_READY", "status", mockResponse.getStatus());
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
}
