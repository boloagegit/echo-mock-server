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
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Response to management integration test
 */
@TestPropertySource(properties = "echo.features.bulk-import-export-enabled=true")
class ResponseManagementIntegrationTest extends BaseIntegrationTest {

    // ========== 9.2 Response CRUD ==========

    @Test
    @DisplayName("Creating a Response returns 201 with id, description, and bodySize")
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
    @DisplayName("Reading a Response returns 200 with its body")
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
    @DisplayName("Updating a Response returns 200 with the changed description")
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
    @DisplayName("Deleting a Response returns 200 with deletedRules")
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

    // ========== 9.3 Response List and Search ==========

    @Test
    @DisplayName("Listing Responses returns all entries")
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
    @DisplayName("Searching Responses returns keyword matches")
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

    // ========== 9.4 Response Summary (including usage quantity) ==========

    @Test
    @DisplayName("Response summaries include id, description, bodySize, and usageCount")
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

    @Test
    @DisplayName("Paginated Response summaries support server search, usage filters, and pagination")
    @SuppressWarnings("unchecked")
    void responseSummaryPage_shouldFilterAndPaginate() {
        Response used = createResponse("分頁搜尋-使用中", "{\"used\":true}");
        createResponse("分頁搜尋-未使用", "{\"unused\":true}");
        RuleDto rule = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey("/api/response-page-search")
                .method("GET")
                .responseId(used.getId())
                .status(200)
                .build();
        adminClient().postForEntity("/api/admin/rules", rule, RuleDto.class);

        ResponseEntity<Map<String, Object>> response = adminClient().exchange(
                "/api/admin/responses/summary?keyword=分頁搜尋&usage=used&page=0&size=1&sort=usageCount&direction=desc",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> page = response.getBody();
        assertThat(page).isNotNull();
        assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(1);
        assertThat(((Number) page.get("totalPages")).intValue()).isEqualTo(1);
        List<Map<String, Object>> results = (List<Map<String, Object>>) page.get("results");
        assertThat(results).hasSize(1);
        assertThat(((Number) results.get(0).get("id")).longValue()).isEqualTo(used.getId());
        assertThat(((Number) results.get(0).get("usageCount")).intValue()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Response-summary search supports exact IDs and escapes wildcard characters")
    @SuppressWarnings("unchecked")
    void responseSummaryPage_shouldSearchByIdAndEscapeWildcards() {
        Response literal = createResponse("摘要-100%_完成", "{\"literal\":true}");
        createResponse("摘要-100X完成", "{\"wildcard\":true}");

        ResponseEntity<Map<String, Object>> byId = adminClient().exchange(
                "/api/admin/responses/summary?keyword=" + literal.getId() + "&page=0&size=20",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        ResponseEntity<Map<String, Object>> byLiteral = adminClient().exchange(
                "/api/admin/responses/summary?keyword={keyword}&page=0&size=20",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {}, "100%_完成");

        List<Map<String, Object>> idRows = (List<Map<String, Object>>) byId.getBody().get("results");
        List<Map<String, Object>> literalRows = (List<Map<String, Object>>)
                byLiteral.getBody().get("results");
        assertThat(idRows).extracting(row -> ((Number) row.get("id")).longValue())
                .contains(literal.getId());
        assertThat(literalRows).extracting(row -> (String) row.get("description"))
                .contains("摘要-100%_完成")
                .doesNotContain("摘要-100X完成");
    }

    @Test
    @DisplayName("Response-summary content-type filters distinguish SSE from ordinary responses")
    @SuppressWarnings("unchecked")
    void responseSummaryPage_shouldFilterSseContentType() {
        Response sse = Response.builder()
                .description("摘要-SSE")
                .body("[{\"data\":\"ready\"}]")
                .contentType("SSE_EVENTS")
                .build();
        ResponseEntity<Response> createdSse = adminClient()
                .postForEntity("/api/admin/responses", sse, Response.class);
        assertThat(createdSse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long sseId = createdSse.getBody().getId();
        Response general = createResponse("摘要-一般", "{\"ready\":true}");

        ResponseEntity<Map<String, Object>> ssePage = adminClient().exchange(
                "/api/admin/responses/summary?keyword=摘要-&contentType=SSE&page=0&size=20",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        ResponseEntity<Map<String, Object>> generalPage = adminClient().exchange(
                "/api/admin/responses/summary?keyword=摘要-&contentType=GENERAL&page=0&size=20",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        List<Map<String, Object>> sseRows = (List<Map<String, Object>>)
                ssePage.getBody().get("results");
        List<Map<String, Object>> generalRows = (List<Map<String, Object>>)
                generalPage.getBody().get("results");
        assertThat(sseRows).extracting(row -> ((Number) row.get("id")).longValue())
                .contains(sseId)
                .doesNotContain(general.getId());
        assertThat(generalRows).extracting(row -> ((Number) row.get("id")).longValue())
                .contains(general.getId())
                .doesNotContain(sseId);
    }

    // ========== 9.5 Response Association Rule Query ==========

    @Test
    @DisplayName("Querying rules associated with a Response returns HTTP and JMS rules")
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

    // ========== 9.6 Delete Response and delete the association rules ==========

    @Test
    @DisplayName("Deleting a Response also deletes its associated rules")
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

    // ========== 9.7 Response batch deletion ==========

    @Test
    @DisplayName("Batch deletion removes Responses successfully")
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

    // ========== 9.8 Response Delete all ==========

    @Test
    @DisplayName("Deleting all Responses also removes associated rules")
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

    // ========== 9.9 Response Export/Import ==========

    @Test
    @DisplayName("Exporting all Responses returns the complete list")
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
    @DisplayName("Bulk import creates Responses successfully")
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
