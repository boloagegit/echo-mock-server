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

/** SSE rule and its response: type, description retained, overridden with rule, type filter. */
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

    /** Send it as JSON like the front end (updateExistingResponse is only used for requests, RuleDto will not be brought during serialization). */
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
    @DisplayName("An SSE rule created by the editor has response type SSE")
    void sseRuleCreatesSseResponse() {
        RuleDto rule = createSseRule("/sse/type");

        assertThat(response(rule.getResponseId())).containsEntry("contentType", "SSE");
    }

    @Test
    @DisplayName("Editing existing SSE events through a rule reuses the Response and preserves its description")
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
    @DisplayName("A failed rule write also rolls back the Response update in the same operation")
    void failedRuleWriteRollsBackTheResponse() {
        RuleDto rule = createSseRule("/sse/atomic");
        RuleDto current = adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class);
        long responsesBefore = responseRepository.count();
        // The rule write fails after the response has been rewritten
        doThrow(new IllegalStateException("simulated database failure")).when(httpProtocolHandler).save(any());

        ResponseEntity<String> saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rewritePayload(current, NEW_EVENTS)), String.class);

        assertThat(saved.getStatusCode().isError()).isTrue();
        assertThat(response(rule.getResponseId())).containsEntry("body", EVENTS);
        assertThat(responseRepository.count()).isEqualTo(responsesBefore);
    }

    @Test
    @DisplayName("Updating only the response body preserves its description and type")
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
    @DisplayName("Resaving a legacy SSE rule marks its previously untyped Response as SSE")
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
    @DisplayName("The SSE filter includes SSE and legacy SSE_EVENTS values; the ordinary-response filter excludes both")
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
    @DisplayName("Clearing a shared ordinary response through a rule empties its body instead of silently reusing the old contents")
    void clearingAnExistingGeneralResponseFromTheRuleSavesTheEmptyBody() {
        Response shared = createResponse("shared general", "{\"ok\":true}");
        RuleDto dto = RuleDto.builder().protocol(Protocol.HTTP).matchKey("/general/clear").method("GET").status(200)
                .responseId(shared.getId()).build();
        RuleDto rule = adminClient().postForEntity("/api/admin/rules", dto, RuleDto.class).getBody();

        RuleDto current = adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class);
        ResponseEntity<RuleDto> saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rewritePayload(current, "")), RuleDto.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().getResponseId()).isEqualTo(shared.getId());
        assertThat(responseRepository.findById(shared.getId()).orElseThrow().getBody()).isEmpty();
    }

    @Test
    @DisplayName("An SSE response cannot be cleared through a rule")
    void clearingAnSseResponseFromTheRuleIsRejected() {
        RuleDto rule = createSseRule("/sse/clear");
        RuleDto current = adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class);

        ResponseEntity<String> saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rewritePayload(current, "")), String.class);

        assertThat(saved.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response(rule.getResponseId())).containsEntry("body", EVENTS);
    }

    @Test
    @DisplayName("Changing an SSE rule back to ordinary content also restores the ordinary Response type")
    void turningAnSseRuleBackIntoAGeneralResponseConvertsItsResponse() {
        RuleDto rule = createSseRule("/sse/back");
        RuleDto current = adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class);
        current.setSseEnabled(false);
        current.setSseLoopEnabled(false);

        ResponseEntity<RuleDto> saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rewritePayload(current, "{\"plain\":true}")), RuleDto.class);

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> after = response(rule.getResponseId());
        assertThat(after).containsEntry("body", "{\"plain\":true}");
        assertThat(after.get("contentType")).isNull();
    }

    @Test
    @DisplayName("Another SSE rule sharing the Response prevents conversion to ordinary content and leaves both rule and Response unchanged")
    void aResponseStillUsedByAnotherSseRuleCannotBecomeGeneral() {
        RuleDto rule = createSseRule("/sse/shared-a");
        RuleDto other = RuleDto.builder().protocol(Protocol.HTTP).matchKey("/sse/shared-b").method("GET").status(200)
                .sseEnabled(true).responseId(rule.getResponseId()).build();
        assertThat(adminClient().postForEntity("/api/admin/rules", other, RuleDto.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        RuleDto current = adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class);
        current.setSseEnabled(false);

        ResponseEntity<String> saved = adminClient().exchange("/api/admin/rules/" + rule.getId(), HttpMethod.PUT,
                new HttpEntity<>(rewritePayload(current, "plain")), String.class);

        assertThat(saved.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response(rule.getResponseId())).containsEntry("body", EVENTS).containsEntry("contentType", "SSE");
        assertThat(adminClient().getForObject("/api/admin/rules/" + rule.getId(), RuleDto.class).getSseEnabled())
                .isTrue();
    }

    @Test
    @DisplayName("Responses marked SSE must contain valid SSE events")
    void sseResponseContentIsValidated() {
        ResponseEntity<String> created = adminClient().postForEntity("/api/admin/responses",
                Map.of("description", "broken", "body", "not json", "contentType", "SSE"), String.class);

        assertThat(created.getStatusCode().is4xxClientError()).isTrue();
    }
}
