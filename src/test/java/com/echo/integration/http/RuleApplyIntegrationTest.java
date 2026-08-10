package com.echo.integration.http;

import com.echo.dto.RuleApplyDocument;
import com.echo.dto.RuleApplyResult;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleApplyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Apply HTTP JSON 建立後回傳可再次套用的 canonical resource")
    void applyHttpRuleCreatesThenUpdatesWithResourceVersion() throws Exception {
        RuleApplyDocument create = httpDocument("/apply/orders", "POST", "initial");

        ResponseEntity<RuleApplyResult> created = apply(create, RuleApplyResult.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        RuleApplyResult createdResult = created.getBody();
        assertThat(createdResult).isNotNull();
        assertThat(createdResult.operation()).isEqualTo("CREATED");
        RuleApplyDocument canonical = createdResult.resource();
        assertThat(canonical.getMetadata().getId()).isNotBlank();
        assertThat(canonical.getMetadata().getResourceVersion()).isNotNull();
        assertThat(canonical.getSpec().getResponseBody())
                .isEqualTo(objectMapper.readTree("{\"message\":\"initial\"}"));

        canonical.getSpec().setDescription("updated through apply");
        canonical.getSpec().setResponseBody(objectMapper.readTree("{\"message\":\"updated\"}"));
        ResponseEntity<RuleApplyResult> updated = apply(canonical, RuleApplyResult.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().operation()).isEqualTo("UPDATED");
        assertThat(updated.getBody().resource().getMetadata().getId())
                .isEqualTo(canonical.getMetadata().getId());
        assertThat(updated.getBody().resource().getMetadata().getResourceVersion())
                .isGreaterThan(canonical.getMetadata().getResourceVersion());
        assertThat(updated.getBody().resource().getSpec().getDescription())
                .isEqualTo("updated through apply");
    }

    @Test
    @DisplayName("重複套用相同 responseBody 時沿用既有 Response")
    void exactReapplyReusesResponse() throws Exception {
        ResponseEntity<RuleApplyResult> created = apply(
                httpDocument("/apply/idempotent", "GET", "same"), RuleApplyResult.class);
        RuleApplyDocument canonical = created.getBody().resource();
        Long originalResponseId = canonical.getSpec().getResponseId();

        ResponseEntity<RuleApplyResult> updated = apply(canonical, RuleApplyResult.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().resource().getSpec().getResponseId()).isEqualTo(originalResponseId);
    }

    @Test
    @DisplayName("舊 resourceVersion 不得覆蓋較新的規則")
    void staleResourceVersionReturnsConflict() throws Exception {
        RuleApplyDocument stale = apply(
                httpDocument("/apply/conflict", "GET", "v1"), RuleApplyResult.class)
                .getBody().resource();
        RuleApplyDocument current = objectMapper.readValue(
                objectMapper.writeValueAsString(stale), RuleApplyDocument.class);
        current.getSpec().setDescription("first update");
        apply(current, RuleApplyResult.class);

        stale.getSpec().setDescription("stale update");
        ResponseEntity<Map> response = apply(stale, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "RESOURCE_VERSION_CONFLICT");
    }

    @Test
    @DisplayName("Apply 可建立 JMS 規則")
    void applyCreatesJmsRule() throws Exception {
        RuleApplyDocument document = RuleApplyDocument.builder()
                .apiVersion(RuleApplyDocument.API_VERSION)
                .kind(RuleApplyDocument.KIND)
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.JMS)
                        .matchKey("ORDER.APPLY.Q")
                        .description("JSON Apply JMS")
                        .enabled(true)
                        .protectedRule(false)
                        .priority(10)
                        .responseBody(objectMapper.getNodeFactory().textNode("accepted"))
                        .delayMs(0L)
                        .build())
                .build();

        ResponseEntity<RuleApplyResult> response = apply(document, RuleApplyResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().resource().getSpec().getProtocol()).isEqualTo(Protocol.JMS);
        assertThat(response.getBody().resource().getSpec().getMatchKey()).isEqualTo("ORDER.APPLY.Q");
        assertThat(response.getBody().resource().getSpec().getResponseBody().textValue()).isEqualTo("accepted");
    }

    @Test
    @DisplayName("Apply 缺少必要欄位時 fail fast")
    void missingMatchKeyReturnsBadRequest() {
        RuleApplyDocument invalid = RuleApplyDocument.builder()
                .apiVersion(RuleApplyDocument.API_VERSION)
                .kind(RuleApplyDocument.KIND)
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.HTTP)
                        .method("GET")
                        .build())
                .build();

        ResponseEntity<Map> response = apply(invalid, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "spec.matchKey is required");
    }

    @Test
    @DisplayName("Apply 遇到拼錯的欄位時不得靜默忽略")
    void unknownSpecFieldReturnsBadRequest() throws Exception {
        String json = """
                {
                  "apiVersion": "echo.mock/v1",
                  "kind": "Rule",
                  "spec": {
                    "protocol": "HTTP",
                    "method": "GET",
                    "matchKey": "/apply/typo",
                    "delayMS": 500
                  }
                }
                """;

        ResponseEntity<Map> response = adminClient().postForEntity(
                "/api/admin/rules/apply", new HttpEntity<>(json, jsonHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Unknown spec field: delayMS");
        assertThat(response.getBody()).containsEntry("validationCode", "UNKNOWN_FIELD");
        assertThat(response.getBody()).containsEntry("path", "spec.delayMS");
    }

    @Test
    @DisplayName("宣告式 schema 列出允許值與協定適用範圍")
    void schemaDescribesAllowedValuesAndApplicability() {
        ResponseEntity<Map> response = adminClient().getForEntity("/api/admin/rules/schema", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("apiVersion", RuleApplyDocument.API_VERSION);
        assertThat((java.util.List<?>) response.getBody().get("fields")).isNotEmpty();
    }

    @Test
    @DisplayName("JMS 文件不得接受 HTTP 專用欄位")
    void jmsDocumentRejectsHttpOnlyFieldWithStructuredError() throws Exception {
        String json = """
                {
                  "apiVersion": "echo.mock/v1",
                  "kind": "Rule",
                  "spec": {
                    "protocol": "JMS",
                    "matchKey": "ORDER.Q",
                    "status": 200,
                    "responseBody": "ok"
                  }
                }
                """;

        ResponseEntity<Map> response = adminClient().postForEntity(
                "/api/admin/rules/apply", new HttpEntity<>(json, jsonHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("validationCode", "NOT_APPLICABLE");
        assertThat(response.getBody()).containsEntry("path", "spec.status");
    }

    @Test
    @DisplayName("既有規則的 protocol 不可透過 Apply 變更")
    void protocolChangeReturnsConflict() throws Exception {
        RuleApplyDocument canonical = apply(
                httpDocument("/apply/protocol", "GET", "http"), RuleApplyResult.class)
                .getBody().resource();
        canonical.getSpec().setProtocol(Protocol.JMS);
        canonical.getSpec().setMethod(null);
        canonical.getSpec().setTargetHost(null);
        canonical.getSpec().setQueryCondition(null);
        canonical.getSpec().setHeaderCondition(null);
        canonical.getSpec().setStatus(null);
        canonical.getSpec().setResponseHeaders(null);
        canonical.getSpec().setSseEnabled(null);
        canonical.getSpec().setSseLoopEnabled(null);
        canonical.getSpec().setResponseContentType(null);
        canonical.getSpec().setAction(null);
        canonical.getSpec().setForwardTargetMode(null);
        canonical.getSpec().setHttpTargetConnectionId(null);

        ResponseEntity<Map> response = apply(canonical, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "PROTOCOL_IMMUTABLE");
    }

    private RuleApplyDocument httpDocument(String path, String method, String message) throws Exception {
        return RuleApplyDocument.builder()
                .apiVersion(RuleApplyDocument.API_VERSION)
                .kind(RuleApplyDocument.KIND)
                .spec(RuleApplyDocument.Spec.builder()
                        .protocol(Protocol.HTTP)
                        .matchKey(path)
                        .method(method)
                        .description("JSON Apply HTTP")
                        .enabled(true)
                        .protectedRule(false)
                        .priority(0)
                        .status(200)
                        .responseHeaders(Map.of("Content-Type", "application/json"))
                        .responseBody(objectMapper.readTree("{\"message\":\"" + message + "\"}"))
                        .delayMs(0L)
                        .action("MOCK")
                        .build())
                .build();
    }

    private <T> ResponseEntity<T> apply(RuleApplyDocument document, Class<T> responseType) {
        return adminClient().exchange(
                "/api/admin/rules/apply",
                HttpMethod.POST,
                new HttpEntity<>(document),
                responseType);
    }

    private org.springframework.http.HttpHeaders jsonHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON));
        return headers;
    }
}
