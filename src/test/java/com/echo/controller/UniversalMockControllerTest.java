package com.echo.controller;

import com.echo.entity.Protocol;
import com.echo.pipeline.HttpMockPipeline;
import com.echo.pipeline.MockRequest;
import com.echo.pipeline.MockResponse;
import com.echo.pipeline.PipelineResult;
import com.echo.service.HttpRuleService;
import com.echo.service.RuleService;
import com.echo.service.RequestLogService;
import com.echo.service.ResponseTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UniversalMockControllerTest {

    @Mock
    @SuppressWarnings("UnusedVariable") // injected via @InjectMocks
    private RuleService ruleService;

    @Mock
    @SuppressWarnings("UnusedVariable") // injected via @InjectMocks
    private HttpRuleService httpRuleService;

    @Mock
    @SuppressWarnings("UnusedVariable") // injected via @InjectMocks
    private RequestLogService requestLogService;

    @Mock
    @SuppressWarnings("UnusedVariable") // injected via @InjectMocks
    private ResponseTemplateService templateService;

    @Mock
    private HttpMockPipeline httpMockPipeline;

    @InjectMocks
    private UniversalMockController controller;

    @SuppressWarnings("unchecked")
    private ResponseEntity<String> getResult(
            org.springframework.web.context.request.async.DeferredResult<ResponseEntity<String>> deferredResult) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!deferredResult.hasResult()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("DeferredResult did not complete within timeout");
            }
            Thread.sleep(5);
        }
        return (ResponseEntity<String>) deferredResult.getResult();
    }

    // ==================== Helper: build PipelineResult ====================

    private PipelineResult matchedResult(int status, String body, long delayMs) {
        return PipelineResult.builder()
                .response(MockResponse.builder()
                        .status(status)
                        .body(body)
                        .matched(true)
                        .forwarded(false)
                        .build())
                .ruleId("uuid-1")
                .matched(true)
                .matchTimeMs(1)
                .responseTimeMs(5)
                .matchChainJson("[]")
                .delayMs(delayMs)
                .build();
    }

    private PipelineResult unmatchedResult(int status, String body) {
        return PipelineResult.builder()
                .response(MockResponse.builder()
                        .status(status)
                        .body(body)
                        .matched(false)
                        .forwarded(false)
                        .build())
                .matched(false)
                .matchTimeMs(0)
                .responseTimeMs(2)
                .matchChainJson("[]")
                .delayMs(0)
                .build();
    }

    private PipelineResult forwardedResult(int status, String body) {
        return PipelineResult.builder()
                .response(MockResponse.builder()
                        .status(status)
                        .body(body)
                        .matched(false)
                        .forwarded(true)
                        .build())
                .matched(false)
                .matchTimeMs(0)
                .responseTimeMs(5)
                .matchChainJson("[]")
                .delayMs(0)
                .build();
    }

    private PipelineResult forwardedErrorResult(int status, String body, String proxyError) {
        return PipelineResult.builder()
                .response(MockResponse.builder()
                        .status(status)
                        .body(body)
                        .matched(false)
                        .forwarded(true)
                        .proxyError(proxyError)
                        .build())
                .matched(false)
                .matchTimeMs(0)
                .responseTimeMs(5)
                .matchChainJson("[]")
                .delayMs(0)
                .build();
    }

    // ==================== Tests ====================

    @Test
    void shouldReturnMockResponse_whenRuleExists() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(matchedResult(200, "{\"status\":\"ok\"}", 0));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/api/test");
        request.addHeader("X-Original-Host", "api.example.com");

        var result = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).contains("status");
    }

    @Test
    void shouldReturn404_whenNoRuleFound() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(unmatchedResult(404, "No matching rule found"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/unknown");

        var result = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(result.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void shouldDelegateToPipeline_whenNoRuleFound() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(unmatchedResult(404, "No matching rule found"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/unknown");
        request.setRemoteAddr("192.168.1.1");

        getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(httpMockPipeline).execute(captor.capture());

        MockRequest mockRequest = captor.getValue();
        assertThat(mockRequest.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(mockRequest.getMethod()).isEqualTo("GET");
        assertThat(mockRequest.getPath()).isEqualTo("/unknown");
        assertThat(mockRequest.getClientIp()).isEqualTo("192.168.1.1");
    }

    @Test
    void shouldUseDefaultHost_whenHeaderMissing() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(matchedResult(200, "{}", 0));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/test");

        var result = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(result.getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(httpMockPipeline).execute(captor.capture());
        assertThat(captor.getValue().getTargetHost()).isEqualTo("default");
    }

    @Test
    void shouldApplyDelay_whenDelayMsSet() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(matchedResult(200, "{}", 100));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/test");
        long start = System.currentTimeMillis();

        var deferredResult = controller.handleRequest(request, new MockHttpServletResponse(), null);
        while (!deferredResult.hasResult()) {
            Thread.sleep(10);
        }

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isGreaterThanOrEqualTo(100);
    }

    @Test
    void shouldReturnJsonContentType_forJsonResponse() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(matchedResult(200, "{\"key\":\"value\"}", 0));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/test");

        var result = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldReturnXmlContentType_forXmlResponse() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(matchedResult(200, "<root><data>test</data></root>", 0));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/test");

        var result = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
    }

    @Test
    void shouldReturnTextContentType_forPlainText() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(matchedResult(200, "plain text response", 0));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/test");

        var result = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    }

    @Test
    void shouldReturnResponseBody_fromPipeline() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(matchedResult(200, "{\"shared\":true}", 0));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/test");

        var result = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(result.getBody()).contains("shared");
        verify(httpMockPipeline).execute(any(MockRequest.class));
    }

    @Test
    void shouldReturnProxyResponse_whenForwardSuccess() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(forwardedResult(200, "proxy response"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/test");
        request.addHeader("X-Original-Host", "api.example.com");
        request.setRemoteAddr("192.168.1.1");

        var result = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo("proxy response");
    }

    @Test
    void shouldReturn502_whenProxyFails() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(forwardedErrorResult(502, "Proxy error: Connection refused", "Connection refused"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/test");
        request.addHeader("X-Original-Host", "api.example.com");
        request.setRemoteAddr("192.168.1.1");

        var result = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(result.getStatusCode().value()).isEqualTo(502);
    }

    @Test
    void shouldPassCorrectMockRequest_toPipeline() throws Exception {
        when(httpMockPipeline.execute(any(MockRequest.class)))
                .thenReturn(matchedResult(200, "{}", 0));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/api/test");
        request.addHeader("X-Original-Host", "api.example.com");
        request.setRemoteAddr("10.0.0.1");

        getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        ArgumentCaptor<MockRequest> captor = ArgumentCaptor.forClass(MockRequest.class);
        verify(httpMockPipeline).execute(captor.capture());

        MockRequest mockRequest = captor.getValue();
        assertThat(mockRequest.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(mockRequest.getMethod()).isEqualTo("GET");
        assertThat(mockRequest.getPath()).isEqualTo("/api/test");
        assertThat(mockRequest.getTargetHost()).isEqualTo("api.example.com");
        assertThat(mockRequest.getClientIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void shouldApplyCustomHeaders_fromPipelineResponse() throws Exception {
        PipelineResult result = PipelineResult.builder()
                .response(MockResponse.builder()
                        .status(200)
                        .body("{\"data\":1}")
                        .headers(Map.of("X-Custom", "test-value"))
                        .matched(true)
                        .forwarded(false)
                        .build())
                .ruleId("uuid-1")
                .matched(true)
                .matchTimeMs(1)
                .responseTimeMs(5)
                .matchChainJson("[]")
                .delayMs(0)
                .build();

        when(httpMockPipeline.execute(any(MockRequest.class))).thenReturn(result);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mock/test");

        var response = getResult(controller.handleRequest(request, new MockHttpServletResponse(), null));

        assertThat(response.getHeaders().getFirst("X-Custom")).isEqualTo("test-value");
    }
}
