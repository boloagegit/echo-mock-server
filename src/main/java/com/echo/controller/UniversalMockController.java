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
 * wildcard HTTP Mock controller
 * <p>
 * Intercept all requests for the /mock/** path and return simulated responses according to the rules:
 * <ol>
 *   <li>gets target host  from X-Original-Host header</li>
 *   <li>based on host + path + method query matching rule</li>
 *   <li>accurately matches  based on body/query conditions</li>
 *   <li>applies delay and then returns a simulated response</li>
 * </ol>
 * 
 * <h3>How to use</h3>
 * Change the original API request to:
 * <pre>
 * Original: GET https://api.example.com/users
 * Change to: GET http://localhost:8080/mock/users
 *       Header: X-Original-Host: api.example.com
 * </pre>
 * 
 * @see RuleService rule matching logic
 */
@RestController
@RequestMapping("/mock")
@Slf4j
public class UniversalMockController {

    /** Original host header name */
    private static final String ORIGINAL_HOST_HEADER = "X-Original-Host";
    /** Default host (used when X-Original-Host is not provided) */
    private static final String DEFAULT_HOST = "default";
    /** Default timeout exceeds the worst-case pool wait of 3 s + connection timeout of 5 s + response timeout of 30 s. */
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 40_000L;
    /** Delayed thread pool size */
    private static final int DELAY_THREAD_POOL_SIZE = 8;

    private final HttpMockPipeline httpMockPipeline;
    private final SseMockHandler sseHandler;
    /** Players created by compatibility constructors need explicit shutdown; the container manages Spring beans. */
    private final boolean ownsSsePlayer;
    private TransactionDiagnostics diagnostics;

    @Autowired
    public void setDiagnostics(TransactionDiagnostics diagnostics) { this.diagnostics = diagnostics; }

    @Value("${echo.http.request-timeout-ms:40000}")
    private long requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS;
    
    /** Delayed response scheduler */
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

    /** For embedded callers and unit tests: create a dedicated SSE handler and player. */
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
     * Processing SSE request (Accept: text/event-stream), delegated to {@link SseMockHandler}.
     * <p>
     * If the rule sseEnabled=true is matched, SseEmitter will be returned;
     * If a non-SSE rule is matched, construct a general ResponseEntity return;
     * If there is no matching rule, GET/HEAD retains 404; other methods delegate generally HTTP pipeline.
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
     * Process the general HTTP Mock request and delegate it to {@link HttpMockPipeline} to execute pipeline.
     * <p>
     * Controller is only responsible for:
     * <ol>
     *   <li>resolves HttpServletRequest to MockRequest</li>
     *   <li>calling pipeline.execute()</li>
     *   <li>converts PipelineResult to ResponseEntity</li>
     *   <li>uses delayScheduler to schedule delayed response</li>
     * </ol>
    */
    @RequestMapping("/**")
    @SuppressWarnings("FutureReturnValueIgnored")
    public Object handleRequest(HttpServletRequest request,
                                HttpServletResponse httpResponse,
                                @RequestBody(required = false) String body) {
        // 1. Parse HttpServletRequest into MockRequest
        Trace trace = diagnostics == null ? Trace.NONE : diagnostics.begin("HTTP");
        MockRequest mockRequest = toMockRequest(request, body, trace);
        trace.event("RECEIVED", "method", mockRequest.getMethod(), "path", mockRequest.getPath());
        log.debug("Mock request: host={}, path={}, method={}",
                mockRequest.getTargetHost(), mockRequest.getPath(), mockRequest.getMethod());

        // 2. Mock is completed directly; the actual downstream I/O uses non-blocking client.
        CompletableFuture<PipelineResult> pipelineFuture =
                httpMockPipeline.executeAsync(mockRequest).toCompletableFuture();
        Runnable connectionReset = captureConnectionReset(httpResponse);

        // Ordinary mock/404 responses already complete on the current thread. Return directly to avoid allocating an MVC async context,
        // DeferredResult, and callbacks; forwarding and delayed replies retain the existing asynchronous flow.
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
     * Convert MockResponse to ResponseEntity
     */
    private ResponseEntity<String> toResponseEntity(MockResponse mockResponse) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(detectContentType(mockResponse.getBody()));

        // Apply custom response headers
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
