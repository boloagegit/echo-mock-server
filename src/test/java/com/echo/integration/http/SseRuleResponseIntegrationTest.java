package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.protocol.http.HttpProtocolHandler;
import com.echo.repository.ResponseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** SSE 規則與它的回應：類型、描述保留、與規則一起改寫、類型篩選。 */
class SseRuleResponseIntegrationTest extends BaseIntegrationTest {

    private static final String EVENTS = "[{\"data\":\"one\",\"delayMs\":10},{\"data\":\"two\",\"delayMs\":10}]";
    private static final String NEW_EVENTS = "[{\"data\":\"changed\",\"delayMs\":5}]";

    @Autowired
    private ResponseRepository responseRepository;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @MockitoSpyBean
    private HttpProtocolHandler httpProtocolHandler;

    private RuleDto createSseRule(String path) {
        RuleDto dto = RuleDto.builder().protocol(Protocol.HTTP).matchKey(path).method("GET").status(200)
                .sseEnabled(true).responseBody(EVENTS).build();
        ResponseEntity<RuleDto> created = adminClient().postForEntity("/api/admin/rules", dto, RuleDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    /** 跟前端一樣以 JSON 送出（updateExistingResponse 只用於請求，RuleDto 序列化時不會帶上）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rewritePayload(RuleDto rule, String events) {
        Map<String, Object> payload = objectMapper.convertValue(rule, Map.class);
        payload.put("responseBody", events);
        payload.put("updateExistingResponse", true);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(Long id) {
        return adminClient().getForObject("/api/admin/responses/" + id, Map.class);
    }

    @Test
    @DisplayName("規則編輯器建立的 SSE 規則，回應類型是 SSE")
    void sseRuleCreatesSseResponse() {
        RuleDto rule = createSseRule("/sse/type");

        assertThat(response(rule.getResponseId())).containsEntry("contentType", "SSE");
    }

    @Test
    @DisplayName("在規則裡改寫既有回應的 SSE 事件：同一個回應、描述保留、不另外建立回應")
    void updatingEventsRewritesTheExistingResponse() {
        RuleDto rule = createSseRule("/sse/rewrite");
        Long responseId = rule.getResponseId();
        String description = (String) response(responseId).get("description");
        long responsesBefore = responseRepository.count();

        RuleDto current = adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class);
        ResponseEntity<RuleDto> saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rewritePayload(current, NEW_EVENTS)), RuleDto.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().getResponseId()).isEqualTo(responseId);
        assertThat(responseRepository.count()).isEqualTo(responsesBefore);
        Map<String, Object> after = response(responseId);
        assertThat(after).containsEntry("body", NEW_EVENTS).containsEntry("contentType", "SSE")
                .containsEntry("description", description);
    }

    @Test
    @DisplayName("規則寫入資料庫失敗時，同一次改寫的回應也一起復原")
    void failedRuleWriteRollsBackTheResponse() {
        RuleDto rule = createSseRule("/sse/atomic");
        RuleDto current = adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class);
        long responsesBefore = responseRepository.count();
        // 回應已改寫之後，規則寫入才失敗
        doThrow(new IllegalStateException("simulated database failure")).when(httpProtocolHandler).save(any());

        ResponseEntity<String> saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rewritePayload(current, NEW_EVENTS)), String.class);

        assertThat(saved.getStatusCode().isError()).isTrue();
        assertThat(response(rule.getResponseId())).containsEntry("body", EVENTS);
        assertThat(responseRepository.count()).isEqualTo(responsesBefore);
    }

    @Test
    @DisplayName("只更新回應內容時，描述與類型保留原值")
    void updatingOnlyTheBodyKeepsDescriptionAndType() {
        RuleDto rule = createSseRule("/sse/keep");
        Long id = rule.getResponseId();
        String description = (String) response(id).get("description");

        ResponseEntity<String> put = adminClient().exchange("/api/admin/responses/" + id, HttpMethod.PUT,
                new HttpEntity<>(Map.of("body", NEW_EVENTS)), String.class);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response(id)).containsEntry("description", description).containsEntry("contentType", "SSE");
    }

    @Test
    @DisplayName("舊版沒有標成 SSE 的回應：規則重新存檔時補上 SSE 類型")
    void legacyUntypedResponseIsMarkedSseWhenTheRuleIsSaved() {
        RuleDto rule = createSseRule("/sse/legacy");
        Response stored = responseRepository.findById(rule.getResponseId()).orElseThrow();
        stored.setContentType(null);
        responseRepository.save(stored);

        RuleDto current = adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class);
        current.setResponseBody(null);
        ResponseEntity<RuleDto> saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(current), RuleDto.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response(rule.getResponseId())).containsEntry("contentType", "SSE");
    }

    @Test
    @DisplayName("SSE 類型篩選找得到 SSE 回應，包含舊值 SSE_EVENTS；一般篩選不包含它們")
    @SuppressWarnings("unchecked")
    void contentTypeFilterFindsSseResponses() {
        Response current = createResponse("current sse", EVENTS);
        Response legacy = createResponse("legacy sse", EVENTS);
        Response plain = createResponse("plain", "{\"ok\":true}");
        for (Response r : List.of(current, legacy)) {
            Response stored = responseRepository.findById(r.getId()).orElseThrow();
            stored.setContentType(r == current ? "SSE" : "SSE_EVENTS");
            responseRepository.save(stored);
        }

        List<Map<String, Object>> sse = (List<Map<String, Object>>) adminClient().getForObject(
                "/api/admin/responses/summary?page=0&size=50&contentType=SSE", Map.class).get("results");
        List<Map<String, Object>> general = (List<Map<String, Object>>) adminClient().getForObject(
                "/api/admin/responses/summary?page=0&size=50&contentType=GENERAL", Map.class).get("results");

        assertThat(sse).extracting(r -> ((Number) r.get("id")).longValue())
                .contains(current.getId(), legacy.getId()).doesNotContain(plain.getId());
        assertThat(sse).allSatisfy(r -> assertThat(r).containsEntry("contentType", "SSE"));
        assertThat(general).extracting(r -> ((Number) r.get("id")).longValue())
                .contains(plain.getId()).doesNotContain(current.getId(), legacy.getId());
    }

    @Test
    @DisplayName("標成 SSE 的回應必須是有效的 SSE 事件")
    void sseResponseContentIsValidated() {
        ResponseEntity<String> created = adminClient().postForEntity("/api/admin/responses",
                Map.of("description", "broken", "body", "not json", "contentType", "SSE"), String.class);

        assertThat(created.getStatusCode().is4xxClientError()).isTrue();
    }
}
