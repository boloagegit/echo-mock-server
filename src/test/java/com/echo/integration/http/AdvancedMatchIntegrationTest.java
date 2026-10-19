package com.echo.integration.http;

import com.echo.dto.RuleApplyDocument;
import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The complete process of structured conditions (matchSpec) from API creation, comparison, editing and retention to v2 declarative file. */
class AdvancedMatchIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SPEC = """
            {"all": [
              {"source": "header", "name": "X-Tenant", "op": "equals", "value": "acme"},
              {"any": [
                {"source": "body", "selector": {"jsonPath": "$.amount"}, "op": "gte", "value": 1000},
                {"source": "query", "name": "vip", "op": "exists"}
              ]},
              {"not": {"source": "body", "selector": {"jsonPath": "$.status"}, "op": "equals", "value": "CANCELLED"}}
            ]}""";

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private RuleDto advancedRule(String path, JsonNode spec, String body) {
        return RuleDto.builder()
                .protocol(Protocol.HTTP).matchKey(path).method("POST")
                .matchSpec(spec).responseBody(body).status(200).sseEnabled(false)
                .build();
    }

    private ResponseEntity<Map> post(RuleDto dto) {
        return adminClient().postForEntity("/api/admin/rules", dto, Map.class);
    }

    private String call(String path, String query, String tenant, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenant != null) {
            headers.set("X-Tenant", tenant);
        }
        String url = "/mock" + path + (query != null ? "?" + query : "");
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class).getBody();
    }

    @Test
    @DisplayName("Advanced all/any/not, numeric, and exists conditions select rules and fall back to an unconditional rule on no match")
    void advancedRuleSelectsRequests() throws Exception {
        assertThat(post(advancedRule("/adv/orders", json(SPEC), "big")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        createHttpRule("/adv/orders", "POST", "default");

        assertThat(call("/adv/orders", null, "acme", "{\"amount\":1500,\"status\":\"NEW\"}")).isEqualTo("big");
        assertThat(call("/adv/orders", "vip=1", "acme", "{\"amount\":10,\"status\":\"NEW\"}")).isEqualTo("big");
        assertThat(call("/adv/orders", null, "acme", "{\"amount\":10,\"status\":\"NEW\"}")).isEqualTo("default");
        assertThat(call("/adv/orders", null, "acme", "{\"amount\":5000,\"status\":\"CANCELLED\"}"))
                .isEqualTo("default");
        assertThat(call("/adv/orders", null, "other", "{\"amount\":5000}")).isEqualTo("default");
    }

    @Test
    @DisplayName("Stores advanced conditions as normalized JSON with empty legacy fields, converting representable conditions back to legacy fields")
    void storageIsCanonicalAndLegacyExpressibleSpecsCollapse() throws Exception {
        RuleDto created = adminClient().postForEntity("/api/admin/rules",
                advancedRule("/adv/store", json(SPEC), "ok"), RuleDto.class).getBody();
        assertThat(created).isNotNull();
        assertThat(created.getAdvancedMatch()).isTrue();
        assertThat(created.getMatchSpec()).isEqualTo(json(SPEC));
        assertThat(created.getBodyCondition()).isNull();
        assertThat(created.getQueryCondition()).isNull();
        assertThat(created.getHeaderCondition()).isNull();

        RuleDto legacy = adminClient().postForEntity("/api/admin/rules", advancedRule("/adv/legacy",
                json("{\"all\":[{\"legacy\":{\"source\":\"query\",\"condition\":\"a=1\"}}]}"), "ok"),
                RuleDto.class).getBody();
        assertThat(legacy).isNotNull();
        assertThat(legacy.getAdvancedMatch()).isFalse();
        assertThat(legacy.getMatchSpec()).isNull();
        assertThat(legacy.getQueryCondition()).isEqualTo("a=1");
    }

    @Test
    @DisplayName("Edits unaware of matchSpec preserve advanced conditions; only an explicit {\"all\":[]} clears them")
    void editsWithoutConditionsPreserveTheAdvancedSpec() throws Exception {
        RuleDto created = adminClient().postForEntity("/api/admin/rules",
                advancedRule("/adv/preserve", json(SPEC), "ok"), RuleDto.class).getBody();
        assertThat(created).isNotNull();

        RuleDto edit = adminClient().getForEntity("/api/admin/rules/" + created.getId(), RuleDto.class).getBody();
        assertThat(edit).isNotNull();
        edit.setMatchSpec(null);
        edit.setDescription("renamed");
        RuleDto updated = adminClient().exchange("/api/admin/rules/" + created.getId(), HttpMethod.PUT,
                new HttpEntity<>(edit), RuleDto.class).getBody();
        assertThat(updated).isNotNull();
        assertThat(updated.getDescription()).isEqualTo("renamed");
        assertThat(updated.getMatchSpec()).isEqualTo(json(SPEC));

        updated.setMatchSpec(json("{\"all\":[]}"));
        RuleDto cleared = adminClient().exchange("/api/admin/rules/" + created.getId(), HttpMethod.PUT,
                new HttpEntity<>(updated), RuleDto.class).getBody();
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMatchSpec()).isNull();
        assertThat(cleared.getAdvancedMatch()).isFalse();
    }

    @Test
    @DisplayName("Saving rejects mixed matchSpec and legacy conditions, invalid operators, or unsupported sources with 400")
    void invalidSpecsAreRejectedOnSave() throws Exception {
        RuleDto conflict = advancedRule("/adv/bad", json(SPEC), "ok");
        conflict.setBodyCondition("a=1");
        ResponseEntity<Map> conflictResponse = post(conflict);
        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) conflictResponse.getBody().get("error")).contains("cannot be combined");

        ResponseEntity<Map> badOp = post(advancedRule("/adv/bad",
                json("{\"all\":[{\"source\":\"query\",\"name\":\"a\",\"op\":\"equalz\",\"value\":\"1\"}]}"), "ok"));
        assertThat(badOp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) badOp.getBody().get("error")).startsWith("Invalid matchSpec: $.all[0].op");

        ResponseEntity<Map> badSource = post(advancedRule("/adv/bad",
                json("{\"source\":\"path\",\"op\":\"equals\",\"value\":\"/x\"}"), "ok"));
        assertThat(badSource.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) badSource.getBody().get("error")).contains("source 'path' is not available");
    }

    @Test
    @DisplayName("v2 documents create rules through spec.match and export advanced rules as v2; v1 rejects spec.match")
    void v2DocumentsCarryMatch() throws Exception {
        String v2 = """
                {"apiVersion": "echo.mock/v2", "kind": "Rule",
                 "spec": {"protocol": "HTTP", "method": "POST", "matchKey": "/adv/apply",
                          "responseBody": "applied", "match": %s}}""".formatted(SPEC);
        ResponseEntity<Map> created = adminClient().postForEntity("/api/admin/rules/apply",
                new HttpEntity<>(v2, jsonHeaders()), Map.class);
        assertThat(created.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);
        assertThat(call("/adv/apply", null, "acme", "{\"amount\":2000}")).isEqualTo("applied");

        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) created.getBody().get("resource");
        @SuppressWarnings("unchecked")
        String id = (String) ((Map<String, Object>) resource.get("metadata")).get("id");
        JsonNode manifest = MAPPER.valueToTree(adminClient()
                .getForEntity("/api/admin/rules/" + id + "/manifest", Map.class).getBody());
        assertThat(manifest.path("apiVersion").asText()).isEqualTo(RuleApplyDocument.API_VERSION_V2);
        assertThat(manifest.path("spec").path("match")).isEqualTo(json(SPEC));
        assertThat(manifest.path("spec").has("bodyCondition")).isFalse();

        ResponseEntity<Map> v1 = adminClient().postForEntity("/api/admin/rules/apply",
                new HttpEntity<>(v2.replace("echo.mock/v2", "echo.mock/v1"), jsonHeaders()), Map.class);
        assertThat(v1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(v1.getBody()).containsEntry("validationCode", "UNKNOWN_FIELD")
                .containsEntry("path", "spec.match");

        ResponseEntity<Map> conflict = adminClient().postForEntity("/api/admin/rules/apply",
                new HttpEntity<>(v2.replace("\"matchKey\"", "\"bodyCondition\": \"a=1\", \"matchKey\""),
                        jsonHeaders()), Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(conflict.getBody()).containsEntry("validationCode", "MATCH_CONFLICT");

        ResponseEntity<Map> invalid = adminClient().postForEntity("/api/admin/rules/apply",
                new HttpEntity<>(v2.replace("\"gte\"", "\"greater\""), jsonHeaders()), Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).containsEntry("validationCode", "INVALID_MATCH")
                .containsEntry("path", "spec.match.all[1].any[0].op");
    }

    @Test
    @DisplayName("The v2 schema adds spec.match while v1 remains the default")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void schemaHasV2Variant() {
        Map v1 = adminClient().getForEntity("/api/admin/rules/schema", Map.class).getBody();
        Map v2 = adminClient().getForEntity("/api/admin/rules/schema?apiVersion=echo.mock/v2", Map.class)
                .getBody();
        assertThat(v1).containsEntry("apiVersion", RuleApplyDocument.API_VERSION);
        assertThat(v2).containsEntry("apiVersion", RuleApplyDocument.API_VERSION_V2);
        assertThat(fieldPaths(v1)).doesNotContain("spec.match");
        assertThat(fieldPaths(v2)).contains("spec.match", "spec.bodyCondition");
    }

    @Test
    @DisplayName("Editor API converts legacy strings to fragments and returns normalized validation results or path-specific errors with 200")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void editorParseAndValidateEndpoints() throws Exception {
        Map parsed = adminClient().postForEntity("/api/admin/rules/match/parse", Map.of(
                "protocol", "HTTP", "bodyCondition", "a=1; b!=2 ;", "queryCondition", "q=1"), Map.class).getBody();
        assertThat((JsonNode) MAPPER.valueToTree(parsed.get("matchSpec"))).isEqualTo(json("""
                {"all": [{"legacy": {"source": "body", "condition": "a=1"}},
                         {"legacy": {"source": "body", "condition": "b!=2"}},
                         {"legacy": {"source": "query", "condition": "q=1"}}]}"""));

        ResponseEntity<Map> advanced = adminClient().postForEntity("/api/admin/rules/match/validate",
                Map.of("protocol", "HTTP", "matchSpec", json(SPEC)), Map.class);
        assertThat(advanced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(advanced.getBody()).containsEntry("valid", true).containsEntry("advanced", true)
                .containsEntry("conditionCount", 4);

        Map legacy = adminClient().postForEntity("/api/admin/rules/match/validate", Map.of("protocol", "HTTP",
                "matchSpec", json("{\"all\":[{\"legacy\":{\"source\":\"query\",\"condition\":\"a=1\"}}]}")), Map.class).getBody();
        assertThat(legacy).containsEntry("valid", true).containsEntry("advanced", false)
                .containsEntry("queryCondition", "a=1");

        ResponseEntity<Map> invalid = adminClient().postForEntity("/api/admin/rules/match/validate",
                Map.of("protocol", "JMS", "matchSpec", json(SPEC)), Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(invalid.getBody()).containsEntry("valid", false).containsEntry("path", "$.all[0].source");
        assertThat((String) invalid.getBody().get("error")).contains("source 'header' is not available");
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> fieldPaths(Map<?, ?> schema) {
        return ((java.util.List<Map<String, Object>>) schema.get("fields")).stream()
                .map(field -> field.get("path")).toList();
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
