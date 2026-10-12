package com.echo.integration.base;

import org.springframework.http.HttpMethod;
import java.util.List;
import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.echo.entity.Response;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整合測試基底類別
 * <p>
 * 提供共用的測試基礎設施：
 * <ul>
 *   <li>Spring Boot 隨機埠啟動</li>
 *   <li>Basic Auth admin client</li>
 *   <li>規則/回應建立 helper methods</li>
 *   <li>每個測試後自動清理資料</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin";
    private static final String ADMIN_API_BASE = "/api/admin";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected CacheManager cacheManager;

    /**
     * 取得帶 Basic Auth (admin/admin) 的 TestRestTemplate
     */
    protected TestRestTemplate adminClient() {
        return restTemplate.withBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    @AfterEach
    void cleanupTestData() {
        TestRestTemplate admin = adminClient();
        // TestRestTemplate.delete 會吞掉錯誤；清理失敗時資料會留給下一個共用同一個 Spring context 的測試，
        // 因此檢查狀態碼，讓問題出現在造成它的測試上
        for (String path : List.of("/rules/all", "/responses/all", "/audit/all")) {
            ResponseEntity<String> response = admin.exchange(ADMIN_API_BASE + path, HttpMethod.DELETE, null, String.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("cleanup DELETE %s returned %s: %s", path, response.getStatusCode(), response.getBody())
                    .isTrue();
        }
        // 清理後確認真的沒有規則；若之後又出現，代表有背景工作或其他測試在同一個 context 建立規則
        ResponseEntity<String> remaining = admin.getForEntity(ADMIN_API_BASE + "/rules", String.class);
        assertThat(remaining.getBody()).as("rules left after cleanup").isEqualTo("[]");
        // 清除規則快取，避免 JMS 等非同步處理使用到已刪除的規則
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    // ========== HTTP Rule Helpers ==========

    /**
     * 建立 HTTP 規則（完整參數）
     */
    protected RuleDto createHttpRule(String matchKey, String method, String responseBody,
                                     String targetHost, Integer status, String bodyCondition,
                                     String queryCondition, String headerCondition,
                                     String responseHeaders, Long delayMs, Integer priority,
                                     String description) {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey(matchKey)
                .method(method)
                .responseBody(responseBody)
                .targetHost(targetHost)
                .status(status != null ? status : 200)
                .bodyCondition(bodyCondition)
                .queryCondition(queryCondition)
                .headerCondition(headerCondition)
                .responseHeaders(responseHeaders)
                .delayMs(delayMs)
                .priority(priority)
                .description(description)
                .sseEnabled(false)
                .build();

        ResponseEntity<RuleDto> response = adminClient()
                .postForEntity(ADMIN_API_BASE + "/rules", dto, RuleDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /**
     * 建立 HTTP 規則（簡易版：matchKey + method + responseBody）
     */
    protected RuleDto createHttpRule(String matchKey, String method, String responseBody) {
        return createHttpRule(matchKey, method, responseBody, null, 200,
                null, null, null, null, null, null, null);
    }

    /**
     * 建立 HTTP 規則（含 targetHost）
     */
    protected RuleDto createHttpRule(String matchKey, String method, String responseBody,
                                     String targetHost) {
        return createHttpRule(matchKey, method, responseBody, targetHost, 200,
                null, null, null, null, null, null, null);
    }

    /**
     * 建立 HTTP 規則（含 targetHost + status）
     */
    protected RuleDto createHttpRule(String matchKey, String method, String responseBody,
                                     String targetHost, Integer status) {
        return createHttpRule(matchKey, method, responseBody, targetHost, status,
                null, null, null, null, null, null, null);
    }

    // ========== SSE Rule Helpers ==========

    /**
     * 建立 SSE 規則
     *
     * @param matchKey  URI 路徑
     * @param sseEvents SSE 事件 JSON 陣列字串，例如：
     *                  [{"type":"normal","data":"hello"},{"type":"normal","data":"world"}]
     * @return 建立的 RuleDto
     */
    protected RuleDto createSseRule(String matchKey, String sseEvents) {
        return createSseRule(matchKey, sseEvents, null, false);
    }

    /**
     * 建立 SSE 規則（含 targetHost 與 loopEnabled）
     */
    protected RuleDto createSseRule(String matchKey, String sseEvents,
                                     String targetHost, boolean loopEnabled) {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.HTTP)
                .matchKey(matchKey)
                .method("GET")
                .responseBody(sseEvents)
                .targetHost(targetHost)
                .status(200)
                .sseEnabled(true)
                .sseLoopEnabled(loopEnabled)
                .build();

        ResponseEntity<RuleDto> response = adminClient()
                .postForEntity(ADMIN_API_BASE + "/rules", dto, RuleDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    // ========== JMS Rule Helpers ==========

    /**
     * 建立 JMS 規則（完整參數）
     */
    protected RuleDto createJmsRule(String queueName, String responseBody,
                                    String bodyCondition, Integer priority,
                                    String description) {
        RuleDto dto = RuleDto.builder()
                .protocol(Protocol.JMS)
                .matchKey(queueName)
                .responseBody(responseBody)
                .bodyCondition(bodyCondition)
                .priority(priority)
                .description(description)
                .build();

        ResponseEntity<RuleDto> response = adminClient()
                .postForEntity(ADMIN_API_BASE + "/rules", dto, RuleDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /**
     * 建立 JMS 規則（簡易版：queueName + responseBody）
     */
    protected RuleDto createJmsRule(String queueName, String responseBody) {
        return createJmsRule(queueName, responseBody, null, null, null);
    }

    // ========== Response Helpers ==========

    /**
     * 建立共用 Response
     */
    protected Response createResponse(String description, String body) {
        Response req = Response.builder()
                .description(description)
                .body(body)
                .build();

        ResponseEntity<Response> response = adminClient()
                .postForEntity(ADMIN_API_BASE + "/responses", req, Response.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }
}
