package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP SSE Rule CRUD Integration Test
 * <p>
 * Test the creation, update, and body verification of SSE rules, and non-SSE rules do not trigger SSE verification.
 */
class SseRuleCrudIntegrationTest extends BaseIntegrationTest {

    private static final String VALID_SSE_BODY =
            "[{\"type\":\"normal\",\"data\":\"hello\"},{\"type\":\"normal\",\"data\":\"world\"}]";

    // ========== 4.2 Test establishment of SSE rules ==========

    @Test
    @DisplayName("Creating an SSE rule with valid events returns 201, sseEnabled=true, sseLoopEnabled=false, and responseContentType=SSE_EVENTS")
    void createSseRule_shouldReturn201WithCorrectFields() {
        RuleDto created = createSseRule("/api/sse/test", VALID_SSE_BODY);

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(created.getMatchKey()).isEqualTo("/api/sse/test");
        assertThat(created.getSseEnabled()).isTrue();
        assertThat(created.getSseLoopEnabled()).isFalse();
        assertThat(created.getResponseContentType()).isEqualTo("SSE_EVENTS");
        assertThat(created.getResponseId()).isNotNull();
    }

    // ========== 4.3 Test Update SSE Rules ==========

    @Test
    @DisplayName("Updating an SSE rule sets sseLoopEnabled=true successfully")
    void updateSseRule_shouldUpdateSseLoopEnabled() {
        RuleDto created = createSseRule("/api/sse/update", VALID_SSE_BODY);

        // GET Obtain complete dto (including version)
        ResponseEntity<RuleDto> getResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        RuleDto toUpdate = getResponse.getBody();
        assertThat(toUpdate).isNotNull();

        toUpdate.setSseLoopEnabled(true);
        // responseBody is required when updating, otherwise the existing responseId will be used because responseBody is empty.
        toUpdate.setResponseBody(VALID_SSE_BODY);

        ResponseEntity<RuleDto> putResponse = adminClient().exchange(
                "/api/admin/rules/" + created.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(toUpdate),
                RuleDto.class);

        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto updated = putResponse.getBody();
        assertThat(updated).isNotNull();
        assertThat(updated.getSseLoopEnabled()).isTrue();
        assertThat(updated.getSseEnabled()).isTrue();

        // Confirm again GET
        ResponseEntity<RuleDto> verifyResponse = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class);
        assertThat(verifyResponse.getBody()).isNotNull();
        assertThat(verifyResponse.getBody().getSseLoopEnabled()).isTrue();
    }

    // ========== 4.4 Test SSE body Verification (end-to-end) ==========

    @Test
    void sseBodyValidation_blankOrNullBody_shouldReturn400() {
        for (String body : java.util.Arrays.asList("", "   ", null)) {
            RuleDto dto = RuleDto.builder().protocol(Protocol.HTTP).matchKey("/api/sse/empty-body")
                    .method("GET").responseBody(body).sseEnabled(true).build();
            assertThat(adminClient().postForEntity("/api/admin/rules", dto, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("SSE validation rejects a JSON string instead of an array with 400")
    void sseBodyValidation_notJsonArray_shouldReturn400() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/sse/not-array")
                .method("GET")
                .responseBody("not a json array")
                .sseEnabled(true)
                .sseLoopEnabled(false)
                .build();

        ResponseEntity<Map> response = adminClient()
                .postForEntity("/api/admin/rules", dto, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("SSE validation rejects a JSON object instead of an array with 400")
    void sseBodyValidation_jsonObjectNotArray_shouldReturn400() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/sse/json-object")
                .method("GET")
                .responseBody("{\"type\":\"normal\",\"data\":\"hello\"}")
                .sseEnabled(true)
                .sseLoopEnabled(false)
                .build();

        ResponseEntity<Map> response = adminClient()
                .postForEntity("/api/admin/rules", dto, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("SSE validation rejects an empty event array with 400")
    void sseBodyValidation_emptyArray_shouldReturn400() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/sse/empty-array")
                .method("GET")
                .responseBody("[]")
                .sseEnabled(true)
                .sseLoopEnabled(false)
                .build();

        ResponseEntity<Map> response = adminClient()
                .postForEntity("/api/admin/rules", dto, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("SSE validation rejects empty event data with 400")
    void sseBodyValidation_emptyEventData_shouldReturn400() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/sse/empty-data")
                .method("GET")
                .responseBody("[{\"type\":\"normal\",\"data\":\"\"}]")
                .sseEnabled(true)
                .sseLoopEnabled(false)
                .build();

        ResponseEntity<Map> response = adminClient()
                .postForEntity("/api/admin/rules", dto, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("SSE validation rejects an invalid event type with 400")
    void sseBodyValidation_invalidType_shouldReturn400() {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/sse/invalid-type")
                .method("GET")
                .responseBody("[{\"type\":\"invalid\",\"data\":\"hello\"}]")
                .sseEnabled(true)
                .sseLoopEnabled(false)
                .build();

        ResponseEntity<Map> response = adminClient()
                .postForEntity("/api/admin/rules", dto, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ========== 4.5 Testing non-SSE rules does not trigger SSE verification ==========

    @Test
    @DisplayName("Non-SSE rules skip SSE validation for arbitrary bodies")
    void nonSseRule_shouldNotTriggerSseValidation() {
        // Use of illegal SSE body (but legal plain text for general HTTP rules)
        RuleDto created = createHttpRule("/api/non-sse", "GET", "this is not a json array");

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getSseEnabled()).isFalse();
        assertThat(created.getResponseContentType()).isNotEqualTo("SSE_EVENTS");
    }

    @Test
    @DisplayName("Non-SSE rules accept an empty array body without SSE validation")
    void nonSseRule_withEmptyArrayBody_shouldSucceed() {
        RuleDto created = createHttpRule("/api/non-sse-array", "GET", "[]");

        assertThat(created.getId()).isNotBlank();
        assertThat(created.getSseEnabled()).isFalse();
    }
}
