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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 匯入/匯出整合測試（HTTP + JMS）
 */
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

    // ========== 8.2 匯出單一規則 ==========

    @Test
    @DisplayName("匯出單一 HTTP 規則 → 確認包含 sseEnabled、sseLoopEnabled 欄位")
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
    @DisplayName("匯出單一 SSE 規則 → 確認 sseEnabled=true")
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
    @DisplayName("匯出單一 JMS 規則 → 確認包含 protocol=JMS")
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

    // ========== 8.3 匯入單一規則 ==========

    @Test
    @DisplayName("匯入 HTTP SSE 規則 → 確認正確建立")
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

        // 透過 GET 驗證已建立
        ResponseEntity<RuleDto> verify = adminClient()
                .getForEntity("/api/admin/rules/" + imported.getId(), RuleDto.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody()).isNotNull();
        assertThat(verify.getBody().getSseEnabled()).isTrue();
    }

    @Test
    @DisplayName("匯入 JMS 規則 → 確認正確建立")
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

        // 透過 GET 驗證已建立
        ResponseEntity<RuleDto> verify = adminClient()
                .getForEntity("/api/admin/rules/" + imported.getId(), RuleDto.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody()).isNotNull();
        assertThat(verify.getBody().getProtocol()).isEqualTo(Protocol.JMS);
    }

    // ========== 8.4 批次匯入 ==========

    @Test
    @DisplayName("批次匯入混合 HTTP + JMS + SSE 規則 → 確認全部正確匯入")
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

        // 驗證全部規則已建立
        ResponseEntity<List> listResponse = adminClient()
                .getForEntity("/api/admin/rules", List.class);
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody()).hasSize(3);
    }

    // ========== 8.5 全量匯出 ==========

    @Test
    @DisplayName("建立混合規則 → 全量匯出確認完整")
    void exportAllRules_shouldContainAllMixedRules() {
        // 先確認目前無規則（@AfterEach 已清理）
        ResponseEntity<List<RuleDto>> preCheck = adminClient().exchange(
                "/api/admin/rules/export",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<RuleDto>>() {});
        assertThat(preCheck.getBody()).isNotNull();
        int existingCount = preCheck.getBody().size();

        // 建立混合規則
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

        // 驗證包含各種協定（只看本測試建立的）
        long httpCount = exported.stream()
                .filter(r -> r.getProtocol() == Protocol.HTTP
                        && r.getMatchKey() != null
                        && r.getMatchKey().startsWith("/api/export-all"))
                .count();
        long jmsCount = exported.stream()
                .filter(r -> r.getProtocol() == Protocol.JMS
                        && "EXPORT-ALL.Q".equals(r.getMatchKey()))
                .count();
        assertThat(httpCount).isEqualTo(2); // 一般 HTTP + SSE
        assertThat(jmsCount).isEqualTo(1);

        // 驗證 SSE 規則欄位
        RuleDto sseExported = exported.stream()
                .filter(r -> "/api/export-all-sse".equals(r.getMatchKey()))
                .findFirst()
                .orElse(null);
        assertThat(sseExported).isNotNull();
        assertThat(sseExported.getSseEnabled()).isTrue();

        // 驗證 JMS 規則欄位
        RuleDto jmsExported = exported.stream()
                .filter(r -> "EXPORT-ALL.Q".equals(r.getMatchKey()))
                .findFirst()
                .orElse(null);
        assertThat(jmsExported).isNotNull();
        assertThat(jmsExported.getProtocol()).isEqualTo(Protocol.JMS);
    }
}
