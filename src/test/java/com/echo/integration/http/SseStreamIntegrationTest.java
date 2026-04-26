package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.HttpRuleRepository;
import com.echo.repository.ResponseRepository;
import com.echo.service.RuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP SSE 串流整合測試
 * <p>
 * 測試 /mock/** 端點的 SSE（Server-Sent Events）串流行為，
 * 包含基本串流、事件格式、延遲、error/abort 行為、fallback、模板渲染等。
 * <p>
 * 使用 java.net.http.HttpClient 讀取 SSE 串流，因為 TestRestTemplate 不支援串流回應。
 */
class SseStreamIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private HttpRuleRepository httpRuleRepository;

    @Autowired
    private ResponseRepository responseRepository;

    @Autowired
    private RuleService ruleService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 發送 SSE 請求並取得完整回應
     */
    private HttpResponse<String> sendSseRequest(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mock" + path))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 從 SSE 回應 body 解析事件列表。
     * 每個事件以空行（\n\n）分隔。
     */
    private List<SseEventData> parseSseEvents(String body) {
        List<SseEventData> events = new ArrayList<>();
        String[] blocks = body.split("\n\n");
        for (String block : blocks) {
            if (block.isBlank()) {
                continue;
            }
            String eventName = null;
            String data = null;
            String id = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length());
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length());
                } else if (line.startsWith("id:")) {
                    id = line.substring("id:".length());
                }
            }
            if (data != null || eventName != null) {
                events.add(new SseEventData(eventName, data, id));
            }
        }
        return events;
    }

    record SseEventData(String event, String data, String id) {}

    // ========== 5.2 測試基本 SSE 串流 ==========

    @Test
    @DisplayName("基本 SSE 串流：3 個 normal 事件 → Content-Type 為 text/event-stream，收到 3 個事件、data 正確")
    void basicSseStream_shouldReturn3EventsWithCorrectData() throws Exception {
        String sseBody = "[{\"type\":\"normal\",\"data\":\"event1\"},"
                + "{\"type\":\"normal\",\"data\":\"event2\"},"
                + "{\"type\":\"normal\",\"data\":\"event3\"}]";
        createSseRule("/stream", sseBody);

        HttpResponse<String> response = sendSseRequest("/stream");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .isPresent()
                .hasValueSatisfying(ct -> assertThat(ct).contains("text/event-stream"));

        List<SseEventData> events = parseSseEvents(response.body());
        assertThat(events).hasSize(3);
        assertThat(events.get(0).data()).isEqualTo("event1");
        assertThat(events.get(1).data()).isEqualTo("event2");
        assertThat(events.get(2).data()).isEqualTo("event3");
    }

    // ========== 5.3 測試 SSE 事件含 event name 與 id ==========

    @Test
    @DisplayName("SSE 事件含 event name 與 id：event=update、id=42 → 驗證 SSE 協定格式")
    void sseEventWithNameAndId_shouldIncludeEventAndIdFields() throws Exception {
        String sseBody = "[{\"type\":\"normal\",\"data\":\"payload\",\"event\":\"update\",\"id\":\"42\"}]";
        createSseRule("/stream-named", sseBody);

        HttpResponse<String> response = sendSseRequest("/stream-named");

        assertThat(response.statusCode()).isEqualTo(200);

        List<SseEventData> events = parseSseEvents(response.body());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("update");
        assertThat(events.get(0).data()).isEqualTo("payload");
        assertThat(events.get(0).id()).isEqualTo("42");

        // 驗證原始 SSE 格式包含 event: 和 id: 行
        assertThat(response.body()).contains("event:update");
        assertThat(response.body()).contains("id:42");
        assertThat(response.body()).contains("data:payload");
    }

    // ========== 5.4 測試 SSE 事件延遲 ==========

    @Test
    @DisplayName("SSE 事件延遲：delayMs=300 → 總耗時 ≥ 300ms")
    void sseEventDelay_shouldTakeAtLeast300ms() throws Exception {
        String sseBody = "[{\"type\":\"normal\",\"data\":\"delayed\",\"delayMs\":300}]";
        createSseRule("/stream-delay", sseBody);

        long start = System.currentTimeMillis();
        HttpResponse<String> response = sendSseRequest("/stream-delay");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elapsed).isGreaterThanOrEqualTo(300L);

        List<SseEventData> events = parseSseEvents(response.body());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("delayed");
    }

    // ========== 5.5 測試 error 事件行為 ==========

    @Test
    @DisplayName("error 事件行為：事件序列含 type=error → 收到 error event 後連線關閉")
    void sseErrorEvent_shouldSendErrorAndClose() throws Exception {
        String sseBody = "[{\"type\":\"normal\",\"data\":\"before-error\"},"
                + "{\"type\":\"error\",\"data\":\"something went wrong\"}]";
        createSseRule("/stream-error", sseBody);

        HttpResponse<String> response = sendSseRequest("/stream-error");

        assertThat(response.statusCode()).isEqualTo(200);

        List<SseEventData> events = parseSseEvents(response.body());
        // 應收到 2 個事件：normal + error
        assertThat(events).hasSize(2);
        assertThat(events.get(0).data()).isEqualTo("before-error");
        // error 事件的 event name 應為 "error"
        assertThat(events.get(1).event()).isEqualTo("error");
        assertThat(events.get(1).data()).isEqualTo("something went wrong");
    }

    // ========== 5.6 測試 abort 事件行為 ==========

    @Test
    @DisplayName("abort 事件行為：事件序列含 type=abort → 連線中斷")
    void sseAbortEvent_shouldDisconnect() throws Exception {
        String sseBody = "[{\"type\":\"normal\",\"data\":\"before-abort\"},"
                + "{\"type\":\"abort\",\"data\":\"abort-data\"}]";
        createSseRule("/stream-abort", sseBody);

        HttpResponse<String> response = sendSseRequest("/stream-abort");

        // abort 會 completeWithError，連線中斷
        // 應至少收到第一個 normal 事件，abort 事件不會被發送
        List<SseEventData> events = parseSseEvents(response.body());
        assertThat(events).hasSizeLessThanOrEqualTo(1);
        if (!events.isEmpty()) {
            assertThat(events.get(0).data()).isEqualTo("before-abort");
        }
    }

    // ========== 5.7 測試非 SSE 規則的 SSE 請求 fallback ==========

    @Test
    @DisplayName("非 SSE 規則的 SSE 請求 fallback：sseEnabled=false → fallback 到一般 ResponseEntity")
    void nonSseRuleFallback_shouldReturnNormalResponse() throws Exception {
        createHttpRule("/stream-fallback", "GET", "{\"fallback\":true}");

        HttpResponse<String> response = sendSseRequest("/stream-fallback");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"fallback\":true}");
    }

    // ========== 5.8 測試 SSE 模板渲染 ==========

    @Test
    @DisplayName("SSE 模板渲染：事件 data 含 {{request.path}} → 渲染後的值正確")
    void sseTemplateRendering_shouldRenderRequestPath() throws Exception {
        String sseBody = "[{\"type\":\"normal\",\"data\":\"path={{request.path}}\"}]";
        createSseRule("/stream-template", sseBody);

        HttpResponse<String> response = sendSseRequest("/stream-template");

        assertThat(response.statusCode()).isEqualTo(200);

        List<SseEventData> events = parseSseEvents(response.body());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("path=/stream-template");
    }

    // ========== 5.9 測試 SSE 規則 responseId 為 null → 500 ==========

    @Test
    @DisplayName("SSE 規則 responseId 為 null → 500")
    void sseRuleWithNullResponseId_shouldReturn500() throws Exception {
        // 先建立 SSE 規則（會自動建立 Response）
        RuleDto created = createSseRule("/stream-no-response",
                "[{\"type\":\"normal\",\"data\":\"test\"}]");

        // 直接透過 repository 將 responseId 設為 null
        httpRuleRepository.findById(created.getId()).ifPresent(rule -> {
            rule.setResponseId(null);
            httpRuleRepository.save(rule);
        });

        HttpResponse<String> response = sendSseRequest("/stream-no-response");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("responseId is null");
    }

    // ========== 5.10 測試 SSE 規則 body 為空事件列表 → 500 ==========

    @Test
    @DisplayName("SSE 規則 body 為空事件列表 → 500")
    void sseRuleWithEmptyEventList_shouldReturn500() throws Exception {
        // 建立 SSE 規則，body 中事件的 data 都為空（會被 parseSseEvents 過濾掉）
        // 先建立合法規則，再直接修改 Response body
        RuleDto created = createSseRule("/stream-empty-events",
                "[{\"type\":\"normal\",\"data\":\"placeholder\"}]");

        // 透過 repository 直接修改 Response body 為只含空 data 的事件（會被過濾）
        Long responseId = created.getResponseId();
        assertThat(responseId).isNotNull();
        responseRepository.findById(responseId).ifPresent(resp -> {
            resp.setBody("[{\"type\":\"normal\",\"data\":\"\"}]");
            responseRepository.save(resp);
        });
        // 清除 response body 快取，確保讀取到更新後的值
        ruleService.evictResponseBodyCache(responseId);

        HttpResponse<String> response = sendSseRequest("/stream-empty-events");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("no valid events");
    }

    // ========== 5.11 測試 SSE 無匹配規則 → 404 ==========

    @Test
    @DisplayName("SSE 無匹配規則 → 404")
    void sseNoMatchingRule_shouldReturn404() throws Exception {
        HttpResponse<String> response = sendSseRequest("/nonexistent-sse-path");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("No mock rule found");
    }
}
