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
 * Integration-test base class.
 * <p>
 * Provide shared testing infrastructure:
 * <ul>
 *   <li>Spring Boot Random port starts</li>
 *   <li>Basic Auth admin client</li>
 *   <li>rule/response established helper methods</li>
 *   <li>Automatically clear data after each test</li>
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
     * Get TestRestTemplate with Basic Auth (admin/admin)
     */
    protected TestRestTemplate adminClient() {
        return restTemplate.withBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    @AfterEach
    void cleanupTestData() {
        TestRestTemplate admin = adminClient();
        // TestRestTemplate.delete will swallow errors; when cleanup fails, the data will be left to the next test that shares the same Spring context.
        // So check the status code and let the problem occur in the test that caused it
        for (String path : List.of("/rules/all", "/responses/all", "/audit/all")) {
            ResponseEntity<String> response = admin.exchange(ADMIN_API_BASE + path, HttpMethod.DELETE, null, String.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("cleanup DELETE %s returned %s: %s", path, response.getStatusCode(), response.getBody())
                    .isTrue();
        }
        // After cleaning, it is confirmed that there are really no rules; if it appears again later, it means that there is background work or other tests to establish rules in the same context
        ResponseEntity<String> remaining = admin.getForEntity(ADMIN_API_BASE + "/rules", String.class);
        assertThat(remaining.getBody()).as("rules left after cleanup").isEqualTo("[]");
        // Clear the rule cache to avoid using deleted rules for asynchronous processing such as JMS
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        });
    }

    // ========== HTTP Rule Helpers ==========

    /**
     * Create HTTP rule (complete parameters)
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
     * Create HTTP rule (simplified version: matchKey + method + responseBody)
     */
    protected RuleDto createHttpRule(String matchKey, String method, String responseBody) {
        return createHttpRule(matchKey, method, responseBody, null, 200,
                null, null, null, null, null, null, null);
    }

    /**
     * Create HTTP rule (including targetHost)
     */
    protected RuleDto createHttpRule(String matchKey, String method, String responseBody,
                                     String targetHost) {
        return createHttpRule(matchKey, method, responseBody, targetHost, 200,
                null, null, null, null, null, null, null);
    }

    /**
     * Create HTTP rule (including targetHost + status)
     */
    protected RuleDto createHttpRule(String matchKey, String method, String responseBody,
                                     String targetHost, Integer status) {
        return createHttpRule(matchKey, method, responseBody, targetHost, status,
                null, null, null, null, null, null, null);
    }

    // ========== SSE Rule Helpers ==========

    /**
     * Create SSE rule
     *
     * @param matchKey  URI path
     * @param sseEvents SSE event JSON array string, for example:
     *                  [{"type":"normal","data":"hello"},{"type":"normal","data":"world"}]
     * @return RuleDto created
     */
    protected RuleDto createSseRule(String matchKey, String sseEvents) {
        return createSseRule(matchKey, sseEvents, null, false);
    }

    /**
     * Create SSE rule (including targetHost and loopEnabled)
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
     * Create JMS rule (complete parameters)
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
     * Create JMS rule (simplified version: queueName + responseBody)
     */
    protected RuleDto createJmsRule(String queueName, String responseBody) {
        return createJmsRule(queueName, responseBody, null, null, null);
    }

    // ========== Response Helpers ==========

    /**
     * Creating share Response
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
