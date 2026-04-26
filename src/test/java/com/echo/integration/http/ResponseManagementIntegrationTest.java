package com.echo.integration.http;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
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
 * 回應管理整合測試
 */
class ResponseManagementIntegrationTest extends BaseIntegrationTest {

    // ========== 9.2 Response CRUD ==========

    @Test
    @DisplayName("建立 Response → 201，包含 id、description、bodySize")
    void createResponse_shouldReturn201() {
        Response req = Response.builder()
                .description("測試回應")
                .body("{\"hello\":\"world\"}")
                .build();

        ResponseEntity<Response> response = adminClient()
                .postForEntity("/api/admin/responses", req, Response.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Response created = response.getBody();
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getDescription()).isEqualTo("測試回應");
        assertThat(created.getBodySize()).isGreaterThan(0);
    }

    @Test
    @DisplayName("讀取 Response → 200，包含 body 內容")
    void getResponse_shouldReturn200WithBody() {
        Response created = createResponse("讀取測試", "{\"data\":\"read\"}");

        ResponseEntity<Response> response = adminClient()
                .getForEntity("/api/admin/responses/" + created.getId(), Response.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Response body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getId()).isEqualTo(created.getId());
        assertThat(body.getDescription()).isEqualTo("讀取測試");
        assertThat(body.getBody()).isEqualTo("{\"data\":\"read\"}");
    }

    @Test
    @DisplayName("更新 Response → 200，description 已更新")
    void updateResponse_shouldReturn200() {
        Response created = createResponse("更新前", "{\"v\":1}");

        Response updateReq = Response.builder()
                .description("更新後")
                .body("{\"v\":2}")
                .build();

        ResponseEntity<Response> updateResp = adminClient().exchange(
                "/api/admin/responses/" + created.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(updateReq),
                Response.class);

        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResp.getBody()).isNotNull();
        assertThat(updateResp.getBody().getDescription()).isEqualTo("更新後");

        ResponseEntity<Response> verify = adminClient()
                .getForEntity("/api/admin/responses/" + created.getId(), Response.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody()).isNotNull();
        assertThat(verify.getBody().getDescription()).isEqualTo("更新後");
        assertThat(verify.getBody().getBody()).isEqualTo("{\"v\":2}");
    }

    @Test
    @DisplayName("刪除 Response → 200，回傳 deletedRules")
    @SuppressWarnings("unchecked")
    void deleteResponse_shouldReturn200() {
        Response created = createResponse("刪除測試", "{\"del\":true}");

        ResponseEntity<Map> response = adminClient().exchange(
                "/api/admin/responses/" + created.getId(),
                HttpMethod.DELETE, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("deletedRules");

        ResponseEntity<Response> verify = adminClient()
                .getForEntity("/api/admin/responses/" + created.getId(), Response.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== 9.3 Response 列表與搜尋 ==========

    @Test
    @DisplayName("列表 Response → 回傳全部")
    void listResponses_shouldReturnAll() {
        createResponse("列表A", "{\"a\":1}");
        createResponse("列表B", "{\"b\":2}");
        createResponse("列表C", "{\"c\":3}");

        ResponseEntity<List<Response>> response = adminClient().exchange(
                "/api/admin/responses", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().hasSizeGreaterThanOrEqualTo(3);
        long count = response.getBody().stream()
                .filter(r -> r.getDescription() != null && r.getDescription().startsWith("列表"))
                .count();
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("搜尋 Response → 回傳符合 keyword 的")
    void searchResponses_shouldReturnMatching() {
        createResponse("訂單回應", "{\"order\":true}");
        createResponse("使用者回應", "{\"user\":true}");
        createResponse("訂單範本", "{\"template\":true}");

        ResponseEntity<List<Response>> response = adminClient().exchange(
                "/api/admin/responses?keyword=訂單", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.getBody()).allMatch(r -> r.getDescription().contains("訂單"));
    }

    // ========== 9.4 Response 摘要（含使用數量） ==========

    @Test
    @DisplayName("Response 摘要 → 回傳 id、description、bodySize、usageCount")
    @SuppressWarnings("unchecked")
    void responseSummary_shouldReturnUsageCount() {
        Response resp = createResponse("摘要測試", "{\"summary\":true}");

        RuleDto httpRule = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/summary-test")
                .method("GET")
                .responseId(resp.getId())
                .status(200)
                .build();
        ResponseEntity<RuleDto> ruleResp = adminClient()
                .postForEntity("/api/admin/rules", httpRule, RuleDto.class);
        assertThat(ruleResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<List<Map<String, Object>>> response = adminClient().exchange(
                "/api/admin/responses/summary", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> summaries = response.getBody();
        assertThat(summaries).isNotNull().isNotEmpty();

        Map<String, Object> summary = summaries.stream()
                .filter(s -> resp.getId().equals(((Number) s.get("id")).longValue()))
                .findFirst().orElse(null);
        assertThat(summary).isNotNull();
        assertThat(summary.get("description")).isEqualTo("摘要測試");
        assertThat(summary.get("bodySize")).isNotNull();
        assertThat(((Number) summary.get("usageCount")).intValue()).isGreaterThan(0);
    }

    // ========== 9.5 Response 關聯規則查詢 ==========

    @Test
    @DisplayName("查詢 Response 關聯規則 → 回傳 HTTP + JMS 規則")
    void getResponseRules_shouldReturnAssociatedRules() {
        Response resp = createResponse("關聯規則測試", "{\"linked\":true}");

        RuleDto httpRule = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/linked-http")
                .method("GET")
                .responseId(resp.getId())
                .status(200)
                .build();
        adminClient().postForEntity("/api/admin/rules", httpRule, RuleDto.class);

        RuleDto jmsRule = RuleDto.builder()
                .protocol(Protocol.JMS)
                .matchKey("LINKED.Q")
                .responseId(resp.getId())
                .build();
        adminClient().postForEntity("/api/admin/rules", jmsRule, RuleDto.class);

        ResponseEntity<List<RuleDto>> response = adminClient().exchange(
                "/api/admin/responses/" + resp.getId() + "/rules",
                HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<RuleDto> rules = response.getBody();
        assertThat(rules).isNotNull().hasSize(2);
        assertThat(rules).anyMatch(r -> r.getProtocol() == Protocol.HTTP
                && "/api/linked-http".equals(r.getMatchKey()));
        assertThat(rules).anyMatch(r -> r.getProtocol() == Protocol.JMS
                && "LINKED.Q".equals(r.getMatchKey()));
    }

    // ========== 9.6 刪除 Response 連帶刪除關聯規則 ==========

    @Test
    @DisplayName("刪除 Response → 關聯規則也被刪除")
    @SuppressWarnings("unchecked")
    void deleteResponse_shouldCascadeDeleteRules() {
        Response resp = createResponse("級聯刪除測試", "{\"cascade\":true}");

        RuleDto httpRule = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/cascade-http")
                .method("GET")
                .responseId(resp.getId())
                .status(200)
                .build();
        ResponseEntity<RuleDto> httpResp = adminClient()
                .postForEntity("/api/admin/rules", httpRule, RuleDto.class);
        String httpRuleId = httpResp.getBody().getId();

        RuleDto jmsRule = RuleDto.builder()
                .protocol(Protocol.JMS)
                .matchKey("CASCADE.Q")
                .responseId(resp.getId())
                .build();
        ResponseEntity<RuleDto> jmsResp = adminClient()
                .postForEntity("/api/admin/rules", jmsRule, RuleDto.class);
        String jmsRuleId = jmsResp.getBody().getId();

        ResponseEntity<Map> deleteResp = adminClient().exchange(
                "/api/admin/responses/" + resp.getId(),
                HttpMethod.DELETE, null, Map.class);

        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) deleteResp.getBody().get("deletedRules")).intValue()).isEqualTo(2);

        ResponseEntity<RuleDto> httpVerify = adminClient()
                .getForEntity("/api/admin/rules/" + httpRuleId, RuleDto.class);
        assertThat(httpVerify.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<RuleDto> jmsVerify = adminClient()
                .getForEntity("/api/admin/rules/" + jmsRuleId, RuleDto.class);
        assertThat(jmsVerify.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ========== 9.7 Response 批次刪除 ==========

    @Test
    @DisplayName("批次刪除 Response → 確認刪除成功")
    @SuppressWarnings("unchecked")
    void batchDeleteResponses_shouldDeleteAll() {
        Response r1 = createResponse("批次1", "{\"b\":1}");
        Response r2 = createResponse("批次2", "{\"b\":2}");
        Response r3 = createResponse("批次3", "{\"b\":3}");

        List<Long> ids = List.of(r1.getId(), r2.getId());

        ResponseEntity<Map> response = adminClient().exchange(
                "/api/admin/responses/batch",
                HttpMethod.DELETE, new HttpEntity<>(ids), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("deleted")).intValue()).isEqualTo(2);

        assertThat(adminClient().getForEntity(
                "/api/admin/responses/" + r1.getId(), Response.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(adminClient().getForEntity(
                "/api/admin/responses/" + r2.getId(), Response.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(adminClient().getForEntity(
                "/api/admin/responses/" + r3.getId(), Response.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ========== 9.8 Response 全部刪除 ==========

    @Test
    @DisplayName("全部刪除 Response → 確認清空（含關聯規則）")
    @SuppressWarnings("unchecked")
    void deleteAllResponses_shouldClearAll() {
        Response resp = createResponse("全刪測試", "{\"all\":true}");
        RuleDto httpRule = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/delete-all")
                .method("GET")
                .responseId(resp.getId())
                .status(200)
                .build();
        adminClient().postForEntity("/api/admin/rules", httpRule, RuleDto.class);
        createResponse("全刪測試2", "{\"all2\":true}");

        ResponseEntity<Map> response = adminClient().exchange(
                "/api/admin/responses/all",
                HttpMethod.DELETE, null, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(((Number) body.get("deletedResponses")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(((Number) body.get("deletedRules")).intValue()).isGreaterThanOrEqualTo(1);

        ResponseEntity<List<Response>> listResp = adminClient().exchange(
                "/api/admin/responses", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(listResp.getBody()).isNotNull().isEmpty();
    }

    // ========== 9.9 Response 匯出/匯入 ==========

    @Test
    @DisplayName("匯出全部 Response → 回傳完整列表")
    void exportResponses_shouldReturnAll() {
        createResponse("匯出A", "{\"export\":\"a\"}");
        createResponse("匯出B", "{\"export\":\"b\"}");

        ResponseEntity<List<Response>> response = adminClient().exchange(
                "/api/admin/responses/export", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().hasSizeGreaterThanOrEqualTo(2);
        long count = response.getBody().stream()
                .filter(r -> r.getDescription() != null && r.getDescription().startsWith("匯出"))
                .count();
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("批次匯入 Response → 匯入成功")
    @SuppressWarnings("unchecked")
    void importResponses_shouldImportAll() {
        List<Response> imports = List.of(
                Response.builder().description("匯入1").body("{\"import\":1}").build(),
                Response.builder().description("匯入2").body("{\"import\":2}").build(),
                Response.builder().description("匯入3").body("{\"import\":3}").build()
        );

        ResponseEntity<Map> response = adminClient()
                .postForEntity("/api/admin/responses/import-batch", imports, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(((Number) response.getBody().get("imported")).intValue()).isEqualTo(3);

        ResponseEntity<List<Response>> listResp = adminClient().exchange(
                "/api/admin/responses", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        assertThat(listResp.getBody()).isNotNull();
        long count = listResp.getBody().stream()
                .filter(r -> r.getDescription() != null && r.getDescription().startsWith("匯入"))
                .count();
        assertThat(count).isEqualTo(3);
    }
}
