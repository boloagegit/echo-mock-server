package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.integration.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Import/export integration test (HTTP + JMS)
 */
@TestPropertySource(properties = "echo.features.bulk-import-export-enabled=true")
class ImportExportIntegrationTest extends BaseIntegrationTest {

    @org.junit.jupiter.api.BeforeEach
    void ensureCleanState() {
        adminClient().exchange("/api/admin/rules/all",
                HttpMethod.DELETE, null, Void.class);
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    // ========== 8.2 Exporting a single rule ==========

    @Test
    @DisplayName("Exporting one HTTP rule includes sseEnabled and sseLoopEnabled")
    void exportSingleHttpRule_shouldContainSseFields() {
        RuleDto created = createHttpRule("/api/export-test", "GET", "{\"data\":\"export\"}");

        ResponseEntity<RuleDto> response = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId() + "/json", RuleDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto exported = response.getBody();
        assertThat(exported).isNotNull();
        assertThat(exported.getId()).isEqualTo(created.getId());
        assertThat(exported.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(exported.getMatchKey()).isEqualTo("/api/export-test");
        assertThat(exported.getSseEnabled()).isNotNull();
        assertThat(exported.getSseLoopEnabled()).isNotNull();
    }

    @Test
    @DisplayName("Exporting one SSE rule includes sseEnabled=true")
    void exportSingleSseRule_shouldHaveSseEnabledTrue() {
        String sseEvents = "[{\"type\":\"normal\",\"data\":\"hello\"}]";
        RuleDto created = createSseRule("/api/export-sse", sseEvents);

        ResponseEntity<RuleDto> response = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId() + "/json", RuleDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto exported = response.getBody();
        assertThat(exported).isNotNull();
        assertThat(exported.getSseEnabled()).isTrue();
    }

    @Test
    @DisplayName("Exporting one JMS rule includes protocol=JMS")
    void exportSingleJmsRule_shouldContainProtocolJms() {
        RuleDto created = createJmsRule("EXPORT.Q", "<export/>");

        ResponseEntity<RuleDto> response = adminClient()
                .getForEntity("/api/admin/rules/" + created.getId() + "/json", RuleDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RuleDto exported = response.getBody();
        assertThat(exported).isNotNull();
        assertThat(exported.getId()).isEqualTo(created.getId());
        assertThat(exported.getProtocol()).isEqualTo(Protocol.JMS);
        assertThat(exported.getMatchKey()).isEqualTo("EXPORT.Q");
    }

    // ========== 8.3 Importing a single rule ==========

    @Test
    @DisplayName("Importing an HTTP SSE rule creates it correctly")
    void importSingleSseRule_shouldCreateSuccessfully() {
        String sseEvents = "[{\"type\":\"normal\",\"data\":\"imported-sse\"}]";
        RuleDto importDto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/imported-sse")
                .method("GET")
                .responseBody(sseEvents)
                .status(200)
                .sseEnabled(true)
                .sseLoopEnabled(false)
                .build();

        ResponseEntity<RuleDto> response = adminClient()
                .postForEntity("/api/admin/rules/import", importDto, RuleDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        RuleDto imported = response.getBody();
        assertThat(imported).isNotNull();
        assertThat(imported.getId()).isNotBlank();
        assertThat(imported.getProtocol()).isEqualTo(Protocol.HTTP);
        assertThat(imported.getMatchKey()).isEqualTo("/api/imported-sse");
        assertThat(imported.getSseEnabled()).isTrue();

        // Verification established via GET
        ResponseEntity<RuleDto> verify = adminClient()
                .getForEntity("/api/admin/rules/" + imported.getId(), RuleDto.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody()).isNotNull();
        assertThat(verify.getBody().getSseEnabled()).isTrue();
    }

    @Test
    @DisplayName("Importing a JMS rule creates it correctly")
    void importSingleJmsRule_shouldCreateSuccessfully() {
        RuleDto importDto = RuleDto.builder()
                .protocol(Protocol.JMS)
                .matchKey("IMPORT.Q")
                .responseBody("<imported/>")
                .build();

        ResponseEntity<RuleDto> response = adminClient()
                .postForEntity("/api/admin/rules/import", importDto, RuleDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        RuleDto imported = response.getBody();
        assertThat(imported).isNotNull();
        assertThat(imported.getId()).isNotBlank();
        assertThat(imported.getProtocol()).isEqualTo(Protocol.JMS);
        assertThat(imported.getMatchKey()).isEqualTo("IMPORT.Q");

        // Verification established via GET
        ResponseEntity<RuleDto> verify = adminClient()
                .getForEntity("/api/admin/rules/" + imported.getId(), RuleDto.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody()).isNotNull();
        assertThat(verify.getBody().getProtocol()).isEqualTo(Protocol.JMS);
    }

    // ========== 8.4 Batch Import ==========

    @Test
    @DisplayName("Bulk import creates mixed HTTP, JMS, and SSE rules correctly")
    @SuppressWarnings("unchecked")
    void batchImportMixedRules_shouldImportAll() {
        RuleDto httpRule = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/batch-http")
                .method("POST")
                .responseBody("{\"batch\":true}")
                .status(200)
                .sseEnabled(false)
                .build();

        String sseEvents = "[{\"type\":\"normal\",\"data\":\"batch-sse\"}]";
        RuleDto sseRule = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/batch-sse")
                .method("GET")
                .responseBody(sseEvents)
                .status(200)
                .sseEnabled(true)
                .sseLoopEnabled(true)
                .build();

        RuleDto jmsRule = RuleDto.builder()
                .protocol(Protocol.JMS)
                .matchKey("BATCH.Q")
                .responseBody("<batch/>")
                .build();

        List<RuleDto> batch = List.of(httpRule, sseRule, jmsRule);

        ResponseEntity<Map> response = adminClient()
                .postForEntity("/api/admin/rules/import-batch", batch, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("imported")).isEqualTo(3);

        // Verify that all rules have been created
        ResponseEntity<List> listResponse = adminClient()
                .getForEntity("/api/admin/rules", List.class);
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody()).hasSize(3);
    }

    // ========== 8.5 Full export ==========

    @Test
    @DisplayName("Exporting all mixed rules preserves the complete set")
    void exportAllRules_shouldContainAllMixedRules() {
        // First confirm that there are currently no rules (@AfterEach has been cleared)
        ResponseEntity<List<RuleDto>> preCheck = adminClient().exchange(
                "/api/admin/rules/export",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<RuleDto>>() {});
        assertThat(preCheck.getBody()).isNotNull();
        int existingCount = preCheck.getBody().size();

        // Creating hybrid rules
        createHttpRule("/api/export-all-http", "GET", "{\"type\":\"http\"}");
        String sseEvents = "[{\"type\":\"normal\",\"data\":\"export-all-sse\"}]";
        createSseRule("/api/export-all-sse", sseEvents);
        createJmsRule("EXPORT-ALL.Q", "<export-all/>");

        ResponseEntity<List<RuleDto>> response = adminClient().exchange(
                "/api/admin/rules/export",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<RuleDto>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<RuleDto> exported = response.getBody();
        assertThat(exported).isNotNull();
        assertThat(exported).hasSize(existingCount + 3);

        // Verification includes various protocols (only those created by this test)
        long httpCount = exported.stream()
                .filter(r -> r.getProtocol() == Protocol.HTTP
                        && r.getMatchKey() != null
                        && r.getMatchKey().startsWith("/api/export-all"))
                .count();
        long jmsCount = exported.stream()
                .filter(r -> r.getProtocol() == Protocol.JMS
                        && "EXPORT-ALL.Q".equals(r.getMatchKey()))
                .count();
        assertThat(httpCount).isEqualTo(2); // General HTTP + SSE
        assertThat(jmsCount).isEqualTo(1);

        // Validate SSE rule field
        RuleDto sseExported = exported.stream()
                .filter(r -> "/api/export-all-sse".equals(r.getMatchKey()))
                .findFirst()
                .orElse(null);
        assertThat(sseExported).isNotNull();
        assertThat(sseExported.getSseEnabled()).isTrue();

        // Validate JMS rule field
        RuleDto jmsExported = exported.stream()
                .filter(r -> "EXPORT-ALL.Q".equals(r.getMatchKey()))
                .findFirst()
                .orElse(null);
        assertThat(jmsExported).isNotNull();
        assertThat(jmsExported.getProtocol()).isEqualTo(Protocol.JMS);
    }
}
