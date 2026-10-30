package com.echo.integration.http;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import com.echo.integration.base.BaseIntegrationTest;
import com.echo.repository.HttpRuleRepository;
import com.echo.repository.HttpTargetConnectionRepository;
import com.echo.repository.ResponseRepository;
import com.echo.service.ResponseService;
import com.echo.service.ScenarioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.*;

/** Portable JSON imports create fresh local records and commit as one transaction. */
@TestPropertySource(
        properties = {
            "echo.features.bulk-import-export-enabled=true",
            "echo.features.scenarios-enabled=true"
        })
class RuleTransferIntegrationTest extends BaseIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired HttpRuleRepository httpRules;
    @Autowired ResponseRepository responses;
    @Autowired HttpTargetConnectionRepository targetConnections;
    @Autowired ScenarioService scenarios;
    @MockitoSpyBean ResponseService responseService;

    @AfterEach
    void clearConnectionFixtures() {
        targetConnections.deleteAll();
    }

    JsonNode spec(String body) {
        return json.valueToTree(
                Map.of(
                        "source",
                        "body",
                        "selector",
                        Map.of("xpath", "//Order/Type[contains(.,'VI')]"),
                        "op",
                        "equals",
                        "value",
                        body));
    }

    RuleDto incoming(String path, String body) {
        return RuleDto.builder()
                .exportVersion(1)
                .protocol(Protocol.HTTP)
                .matchKey(path)
                .method("GET")
                .status(200)
                .responseId(9001L)
                .responseBody(body)
                .responseDescription("source response")
                .enabled(true)
                .build();
    }

    JsonNode node(Object value) {
        return json.valueToTree(value);
    }

    JsonNode get(String path) {
        return adminClient().getForObject(path, JsonNode.class);
    }

    ResponseEntity<JsonNode> post(String path, Object payload) {
        return adminClient().postForEntity(path, payload, JsonNode.class);
    }

    JsonNode exportOne(String id) {
        return get("/api/admin/rules/" + id + "/json");
    }

    JsonNode importBatch(Object rules) {
        var r = post("/api/admin/rules/import-batch", rules);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    String importedId(JsonNode result, int index) {
        return result.path("rules").get(index).path("id").asText();
    }

    void assertReply(String path, String body) {
        var r = restTemplate.getForEntity("/mock" + path, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEqualTo(body);
    }

    JsonNode connection(String name) {
        var result =
                post(
                        "/api/admin/http-target-connections",
                        Map.of(
                                "name",
                                name,
                                "baseUrl",
                                "http://127.0.0.1:1",
                                "authType",
                                "NONE",
                                "connectTimeoutSeconds",
                                2,
                                "readTimeoutSeconds",
                                2,
                                "enabled",
                                true,
                                "tlsVerificationEnabled",
                                true));
        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        return result.getBody();
    }

    JsonNode preview(List<RuleDto> rules) {
        var r =
                post(
                        "/api/admin/rules/import/preview",
                        Map.of("rules", rules, "connectionMappings", Map.of()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    String choice(JsonNode preview, String mode, String id) {
        for (var option : preview.path("connections")) {
            if (mode.equals(option.path("mode").asText())
                    && (id == null || id.equals(option.path("id").asText()))) {
                return option.path("selection").asText();
            }
        }
        throw new AssertionError("No matching destination option");
    }

    @Test
    void singleAndBulkExportContainTheCompleteResponseWithoutChangingTheRule() {
        var created = createHttpRule("/portable-export", "GET", "完整回應 😺\nsecond line");
        var before = get("/api/admin/rules/" + created.getId());
        var single = exportOne(created.getId());
        assertThat(single.path("responseBody").asText()).isEqualTo("完整回應 😺\nsecond line");
        assertThat(single.path("exportVersion").asInt()).isEqualTo(1);
        assertThat(single.path("responseDescription").isTextual()).isTrue();
        var all = get("/api/admin/rules/export");
        assertThat(all).hasSize(1);
        assertThat(all.get(0)).isEqualTo(single);
        assertThat(get("/api/admin/rules/" + created.getId())).isEqualTo(before);
    }

    @Test
    void incomingIdentitiesAndTimestampsCannotOverwriteExistingDestinationData() {
        var existing = createHttpRule("/keep-local", "GET", "LOCAL-CONTENT");
        var before = exportOne(existing.getId());
        var incoming = incoming("/new-copy", "SOURCE-CONTENT");
        incoming.setId(existing.getId());
        incoming.setVersion(77L);
        incoming.setResponseId(existing.getResponseId());
        incoming.setCreatedAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        incoming.setExtendedAt(LocalDateTime.of(2001, 1, 1, 0, 0));
        incoming.setUpdatedAt(LocalDateTime.of(2002, 1, 1, 0, 0));
        incoming.setCreatedBy("source-user");
        incoming.setUpdatedBy("source-user");
        incoming.setUpdateExistingResponse(true);
        var started = LocalDateTime.now().minusSeconds(1);
        var result = importBatch(List.of(incoming));
        var id = importedId(result, 0);
        assertThat(id).isNotEqualTo(existing.getId());
        var stored = httpRules.findById(id).orElseThrow();
        assertThat(stored.getCreatedAt()).isAfter(started);
        assertThat(stored.getExtendedAt()).isNull();
        assertThat(stored.getVersion()).isZero();
        assertThat(stored.getCreatedBy()).isNotEqualTo("source-user");
        assertThat(stored.getResponseId()).isNotEqualTo(existing.getResponseId());
        assertThat(exportOne(existing.getId())).isEqualTo(before);
        assertReply("/keep-local", "LOCAL-CONTENT");
        assertReply("/new-copy", "SOURCE-CONTENT");
    }

    @Test
    void repeatedImportsAreNewCopiesAndPreserveTheSourceEnabledFlags() {
        var enabled = incoming("/enabled-copy", "enabled");
        enabled.setId(UUID.randomUUID().toString());
        var disabled = incoming("/disabled-copy", "disabled");
        disabled.setId(UUID.randomUUID().toString());
        disabled.setResponseId(9002L);
        disabled.setEnabled(false);
        var first = importBatch(List.of(enabled, disabled));
        var snapshot = exportOne(importedId(first, 0));
        var second = importBatch(List.of(enabled, disabled));
        assertThat(importedId(second, 0)).isNotEqualTo(importedId(first, 0));
        assertThat(exportOne(importedId(first, 0))).isEqualTo(snapshot);
        assertThat(httpRules.findById(importedId(second, 0)).orElseThrow().getEnabled()).isTrue();
        assertThat(httpRules.findById(importedId(second, 1)).orElseThrow().getEnabled()).isFalse();
        assertThat(httpRules.count()).isEqualTo(4);
        assertReply("/enabled-copy", "enabled");
        assertThat(restTemplate.getForEntity("/mock/disabled-copy", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sharedResponsesAreCopiedOnceAndNeverAliasTheDestinationResponse() {
        var existing = createHttpRule("/keep-shared", "GET", "LOCAL");
        var first = incoming("/shared-a", "SOURCE-SHARED");
        var second = incoming("/shared-b", "SOURCE-SHARED");
        first.setResponseId(existing.getResponseId());
        second.setResponseId(existing.getResponseId());
        var result = importBatch(List.of(first, second));
        assertThat(result.path("responsesCreated").asInt()).isEqualTo(1);
        var a = exportOne(importedId(result, 0));
        var b = exportOne(importedId(result, 1));
        assertThat(a.path("responseId")).isEqualTo(b.path("responseId"));
        assertThat(a.path("responseId").asLong()).isNotEqualTo(existing.getResponseId());
        assertReply("/keep-shared", "LOCAL");
        assertReply("/shared-a", "SOURCE-SHARED");
        assertReply("/shared-b", "SOURCE-SHARED");
    }

    @Test
    void inconsistentSharedContentAndOldMetadataOnlyFilesAreRejectedBeforeWriting() {
        long before = responses.count();
        var first = incoming("/one", "one");
        var second = incoming("/two", "two");
        var r = post("/api/admin/rules/import-batch", List.of(first, second));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var old = incoming("/old", null);
        old.setExportVersion(null);
        var oldResult = post("/api/admin/rules/import-batch", List.of(old));
        assertThat(oldResult.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(oldResult.getBody().path("errors").get(0).path("code").asText())
                .isEqualTo("RESPONSE_BODY_MISSING");
        assertThat(httpRules.count()).isZero();
        assertThat(responses.count()).isEqualTo(before);
    }

    @Test
    void intentionalNullAndEmptyBodiesArePreservedByPortableExports() {
        var a = incoming("/null-body", null);
        var b = incoming("/empty-body", "");
        b.setResponseId(9002L);
        var result = importBatch(List.of(a, b));
        var nullResponse = httpRules.findById(importedId(result, 0)).orElseThrow().getResponseId();
        var emptyResponse = httpRules.findById(importedId(result, 1)).orElseThrow().getResponseId();
        assertThat(responses.findById(nullResponse).orElseThrow().getBody()).isNull();
        assertThat(responses.findById(emptyResponse).orElseThrow().getBody()).isEmpty();
        assertThat(exportOne(importedId(result, 0)).path("exportVersion").asInt()).isEqualTo(1);
    }

    @Test
    void invalidSecondRuleDoesNotLeaveTheFirstRuleOrItsResponse() {
        var existing = createHttpRule("/keep-atomic", "GET", "keep");
        long count = responses.count();
        var before = exportOne(existing.getId());
        var good = incoming("/atomic-first", "first");
        var bad = incoming("/atomic-second", "second");
        bad.setResponseId(9002L);
        bad.setMatchSpec(
                node(
                        Map.of(
                                "source",
                                "body",
                                "selector",
                                Map.of("raw", true),
                                "op",
                                "unknown-operation",
                                "value",
                                "x")));
        var result = post("/api/admin/rules/import-batch", List.of(good, bad));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(httpRules.count()).isEqualTo(1);
        assertThat(responses.count()).isEqualTo(count);
        assertThat(exportOne(existing.getId())).isEqualTo(before);
        assertReply("/keep-atomic", "keep");
    }

    @Test
    void aLateStorageFailureRollsBackRulesAndResponsesAlreadyWrittenInTheBatch() {
        createHttpRule("/keep-late", "GET", "keep");
        long count = responses.count();
        doAnswer(
                        call -> {
                            Response value = call.getArgument(0);
                            if ("force-storage-failure".equals(value.getBody())) {
                                throw new IllegalStateException("synthetic storage failure");
                            }
                            return call.callRealMethod();
                        })
                .when(responseService)
                .save(any(Response.class));
        var good = incoming("/late-first", "first");
        var bad = incoming("/late-second", "force-storage-failure");
        bad.setResponseId(9002L);
        var result = post("/api/admin/rules/import-batch", List.of(good, bad));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(httpRules.count()).isEqualTo(1);
        assertThat(responses.count()).isEqualTo(count);
        assertReply("/keep-late", "keep");
    }

    @Test
    void forwardingRequiresExplicitDestinationChoiceAndDoesNotModifyConnections() {
        var unrelated = connection("unrelated-local");
        var selected = connection("selected-local");
        var before = get("/api/admin/http-target-connections");
        var rule = incoming("/forward-copy", null);
        rule.setAction("FORWARD");
        rule.setForwardTargetMode("CONNECTION");
        rule.setHttpTargetConnectionId(unrelated.path("id").asLong());
        var preview = preview(List.of(rule));
        assertThat(preview.path("requirements")).hasSize(1);
        assertThat(httpRules.count()).isZero();
        var rejected = post("/api/admin/rules/import-batch", List.of(rule));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String key = preview.path("requirements").get(0).path("key").asText();
        String selection = choice(preview, "CONNECTION", selected.path("id").asText());
        var result =
                post(
                        "/api/admin/rules/import/confirm",
                        Map.of(
                                "rules",
                                List.of(rule),
                                "connectionMappings",
                                Map.of(key, selection)));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        var stored = httpRules.findById(importedId(result.getBody(), 0)).orElseThrow();
        assertThat(stored.getHttpTargetConnectionId()).isEqualTo(selected.path("id").asLong());
        assertThat(get("/api/admin/http-target-connections")).isEqualTo(before);
        assertThat(responses.count()).isZero();
    }

    @Test
    void staleConnectionSelectionsAreRejectedWithoutImportingAnything() {
        var selected = connection("before-edit");
        var rule = incoming("/stale-connection", null);
        rule.setAction("FORWARD");
        rule.setForwardTargetMode("CONNECTION");
        rule.setHttpTargetConnectionId(1234L);
        var preview = preview(List.of(rule));
        String key = preview.path("requirements").get(0).path("key").asText();
        String selection = choice(preview, "CONNECTION", selected.path("id").asText());
        var updated = new LinkedHashMap<String, Object>();
        updated.put("name", "after-edit");
        updated.put("baseUrl", "http://127.0.0.1:1");
        updated.put("authType", "NONE");
        updated.put("connectTimeoutSeconds", 2);
        updated.put("readTimeoutSeconds", 2);
        updated.put("enabled", true);
        updated.put("tlsVerificationEnabled", true);
        updated.put("version", selected.path("version").asLong());
        var edit =
                adminClient()
                        .exchange(
                                "/api/admin/http-target-connections/"
                                        + selected.path("id").asText(),
                                HttpMethod.PUT,
                                new HttpEntity<>(updated),
                                JsonNode.class);
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.OK);
        var before = get("/api/admin/http-target-connections");
        var result =
                post(
                        "/api/admin/rules/import/confirm",
                        Map.of(
                                "rules",
                                List.of(rule),
                                "connectionMappings",
                                Map.of(key, selection)));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(httpRules.count()).isZero();
        assertThat(responses.count()).isZero();
        assertThat(get("/api/admin/http-target-connections")).isEqualTo(before);
    }

    @Test
    void structuredXPathAndSseFieldsSurvivePortableImport() {
        var xml = incoming("/xpath-copy", "XML HIT");
        xml.setMethod("POST");
        xml.setMatchSpec(spec("VIP"));
        var sse = incoming("/sse-copy", "[{\"type\":\"normal\",\"data\":\"消息 😺\"}]");
        sse.setResponseId(9002L);
        sse.setSseEnabled(true);
        sse.setSseLoopEnabled(false);
        var result = importBatch(List.of(xml, sse));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        var hit =
                restTemplate.exchange(
                        "/mock/xpath-copy",
                        HttpMethod.POST,
                        new HttpEntity<>("<Order><Type>VIP</Type></Order>", headers),
                        String.class);
        assertThat(hit.getBody()).isEqualTo("XML HIT");
        var exported = exportOne(importedId(result, 1));
        assertThat(exported.path("sseEnabled").asBoolean()).isTrue();
        assertThat(exported.path("responseBody").asText()).isEqualTo(sse.getResponseBody());
    }

    @Test
    void importingScenarioRulesDoesNotResetDestinationScenarioState() {
        scenarios.advanceState("local-workflow", "Started", "LocalState");
        var rule = incoming("/scenario-copy", "scenario");
        rule.setScenarioName("local-workflow");
        rule.setRequiredScenarioState("Started");
        rule.setNewScenarioState("SourceNext");
        importBatch(List.of(rule));
        assertThat(scenarios.getCurrentState("local-workflow")).isEqualTo("LocalState");
    }

    @Test
    void previewAndConfirmationRequireAuthentication() {
        var request =
                Map.of("rules", List.of(incoming("/unauth", "x")), "connectionMappings", Map.of());
        assertThat(
                        restTemplate
                                .postForEntity(
                                        "/api/admin/rules/import/preview", request, String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        restTemplate
                                .postForEntity(
                                        "/api/admin/rules/import/confirm", request, String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(httpRules.count()).isZero();
        assertThat(responses.count()).isZero();
    }

    @Test
    void httpAndJmsRulesCanShareAFreshResponseWithoutChangingTheDestinationCopy() {
        var existing = createHttpRule("/keep-mixed", "GET", "LOCAL-MIXED");
        var http = incoming("/mixed-http", "<Reply>跨環境 😺</Reply>");
        http.setResponseId(existing.getResponseId());
        var jms = incoming("TRANSFER.MIXED", "<Reply>跨環境 😺</Reply>");
        jms.setResponseId(existing.getResponseId());
        jms.setProtocol(Protocol.JMS);
        jms.setMethod(null);
        var result = importBatch(List.of(http, jms));
        assertThat(result.path("imported").asInt()).isEqualTo(2);
        assertThat(result.path("responsesCreated").asInt()).isEqualTo(1);
        var a = exportOne(importedId(result, 0));
        var b = exportOne(importedId(result, 1));
        assertThat(a.path("responseId")).isEqualTo(b.path("responseId"));
        assertThat(b.path("protocol").asText()).isEqualTo("JMS");
        assertThat(b.path("responseBody").asText()).isEqualTo("<Reply>跨環境 😺</Reply>");
        assertReply("/keep-mixed", "LOCAL-MIXED");
    }

    @Test
    void unsupportedExportVersionsCannotCreateAnyRuleOrResponse() {
        var rule = incoming("/future-version", "future");
        rule.setExportVersion(999);
        var result = post("/api/admin/rules/import-batch", List.of(rule));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().path("errors").get(0).path("code").asText())
                .isEqualTo("UNSUPPORTED_EXPORT_VERSION");
        assertThat(httpRules.count()).isZero();
        assertThat(responses.count()).isZero();
    }

    @Test
    void malformedForwardModesCannotBeHiddenByDestinationMappings() {
        var rule = incoming("/invalid-mode", null);
        rule.setAction("FORWARD");
        rule.setForwardTargetMode("UNKNOWN_MODE");
        var view = preview(List.of(rule));
        assertThat(view.path("errors").get(0).path("code").asText()).isEqualTo("INVALID_RULE");
        var result =
                post(
                        "/api/admin/rules/import/confirm",
                        Map.of(
                                "rules",
                                List.of(rule),
                                "connectionMappings",
                                Map.of("HTTP:UNKNOWN_MODE", "HTTP:ORIGINAL_HOST")));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        rule.setProtocol(Protocol.JMS);
        rule.setAction("TYPO");
        assertThat(post("/api/admin/rules/import-batch", List.of(rule)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(httpRules.count()).isZero();
        assertThat(responses.count()).isZero();
    }
}
